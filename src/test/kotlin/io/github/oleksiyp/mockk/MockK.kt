package io.github.oleksiyp.mockk

import io.github.oleksiyp.mockk.MockK.Companion.anyValue
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javassist.util.proxy.ProxyObject
import java.lang.reflect.Method
import java.util.*
import java.util.Collections.synchronizedList

// ---------------------------- USER FACING --------------------------------

inline fun <reified T> mockk(): T = MockK.mockk(T::class.java)

class EqMatcher<T>(val value: T) : Matcher<T> {
    override fun match(arg: T): Boolean = arg == value

    override fun toString(): String = "eq(" + value + ")"
}

fun <T> on(mockBlock: MockKScope.() -> T): OngoingStubbing<T> {
    MockK.LOCATOR().callRecorder.startRecording()
    MockKScope().mockBlock()
    return OngoingStubbing()
}

class MockKScope {
    inline fun <reified T> eq(value: T): T? = MockK.eq(value)
}

class OngoingStubbing<T> {
    infix fun doReturn(returnValue: T?) {
        MockK.LOCATOR().callRecorder.answer(ConstantAnswer(returnValue))
    }
}

// ---------------------------- INTERFACES --------------------------------
interface MockK {
    companion object {
        val defaultImpl = MockKImpl()
        var LOCATOR: () -> MockK = { defaultImpl }

        private val NO_ARGS_TYPE = Class.forName("\$NoArgsConstructorParamType")

        fun <T> mockk(cls: Class<T>): T {
            val factory = ProxyFactory()

            val obj = if (cls.isInterface) {
                factory.interfaces = arrayOf(cls, MockKInstance::class.java)
                factory.create(emptyArray(), emptyArray())
            } else {
                factory.interfaces = arrayOf(MockKInstance::class.java)
                factory.superclass = cls
                factory.create(arrayOf(MockK.NO_ARGS_TYPE), arrayOf<Any?>(null))
            }
            (obj as ProxyObject).handler = MockKHandler(cls, obj)
            return cls.cast(obj)
        }

        fun anyValue(type: Class<*>, block: () -> Any?): Any? {
            return when (type) {
                Boolean::class.java -> false
                Byte::class.java -> 0.toByte()
                Short::class.java -> 0.toShort()
                Int::class.java -> 0
                Long::class.java -> 0L
                Float::class.java -> 0.0F
                Double::class.java -> 0.0
                String::class.java -> ""
                else -> block()
            }

        }

        inline fun <reified T> eq(value: T): T? {
            MockK.LOCATOR().callRecorder.addMatcher(EqMatcher(value));
            return MockK.anyValue(T::class.java) { null } as T?
        }
    }

    val callRecorder: CallRecorder

}

interface CallRecorder {
    fun startRecording()

    fun addMatcher(matcher: Matcher<*>)

    fun addCall(invocation: Invocation): Any?

    fun answer(answer: Answer<*>)
}

data class Invocation(val self: MockKInstance,
                      val method: Method,
                      val args: List<Any>)

data class InvocationMatcher(val self: Matcher<Any>,
                             val method: Matcher<Method>,
                             val args: List<Matcher<Any>>) {
    fun match(invocation: Invocation): Boolean {
        if (!self.match(invocation.self)) {
            return false
        }
        if (!method.match(invocation.method)) {
            return false
        }
        if (args.size != invocation.args.size) {
            return false
        }

        for (i in 0 until args.size) {
            if (!args[i].match(invocation.args[i])) {
                return false
            }
        }

        return true
    }
}

interface Matcher<in T> {
    fun match(arg: T): Boolean
}

interface Answer<T> {
    fun answer(invocation: Invocation): T
}

interface MockKInstance {
    fun type(): Class<*>

    fun addAnswer(matcher: InvocationMatcher, answer: Answer<*>)

    fun findAnswer(invocation: Invocation): Answer<*>

    override fun toString(): String

    override fun equals(other: Any?): Boolean

    override fun hashCode(): Int

    fun childMockK(invocation: Invocation): MockKInstance
}

class MockKHandler(private val cls: Class<*>,
                   private val obj: Any) : MethodHandler, MockKInstance {
    private val answers = synchronizedList(mutableListOf<Pair<InvocationMatcher, Answer<*>>>())

    override fun addAnswer(matcher: InvocationMatcher, answer: Answer<*>) {
        println(matcher.toString() + " " + answer)
        answers.add(Pair(matcher, answer))
    }

    override fun findAnswer(invocation: Invocation): Answer<*> {
        return synchronized(answers) {
            answers.firstOrNull { it.first.match(invocation) }?.second
                    ?: ConstantAnswer(anyValue(invocation.method.returnType) { null })
        }
    }

    override fun type(): Class<*> = cls

    override fun toString() = "mockk<" + type().name + ">()"

    override fun equals(other: Any?): Boolean {
        return obj === other
    }

    override fun hashCode(): Int {
        return System.identityHashCode(obj)
    }

    override fun childMockK(invocation: Invocation): MockKInstance {
        val mockk = MockK.mockk(invocation.method.returnType)
        return mockk as MockKInstance
    }

    override fun invoke(self: Any,
                        thisMethod: Method,
                        proceed: Method?,
                        args: Array<out Any>): Any? {

        findDeclaredMethod(this, thisMethod)?.let {
            return it.invoke(this, *args)
        }

        val argList = args.toList()
        val invocation = Invocation(self as MockKInstance, thisMethod, argList)
        return MockK.LOCATOR().callRecorder.addCall(invocation)
    }

    private fun findDeclaredMethod(obj: Any,
                                   method: Method): Method? {
        return obj.javaClass.declaredMethods.find {
            it.name == method.name &&
                    Arrays.equals(it.parameterTypes, method.parameterTypes)
        }
    }
}

// ---------------------------- IMPLEMENTATION --------------------------------

data class ConstantAnswer<T>(val constantValue: T?) : Answer<T?> {
    override fun answer(invocation: Invocation) = constantValue
}

class MockKImpl : MockK {
    val callRecorderTL = ThreadLocal.withInitial { CallRecorderImpl() }
    override val callRecorder: CallRecorder
        get() = callRecorderTL.get()

    private data class Call(val invocation: Invocation, val matchers: List<Matcher<*>>)

    inner class CallRecorderImpl : CallRecorder {
        private val calls = mutableListOf<Call>()

        val matchers = mutableListOf<Matcher<*>>()
        var recordingStarted = -1

        override fun startRecording() {
            recordingStarted = calls.size
        }

        override fun addMatcher(matcher: Matcher<*>) {
            matchers.add(matcher)
        }

        override fun addCall(invocation: Invocation): Any? {
            if (recordingStarted == -1) {
                return invocation.self.findAnswer(invocation).answer(invocation)
            }

            if (matchers.size == 0) {
                matchers.addAll(invocation.args.map { EqMatcher(it) })
            }

            if (matchers.size != invocation.method.parameterCount) {
                throw RuntimeException("wrong matchers count")
            }

            calls.add(Call(invocation, matchers.toList()))

            matchers.clear()

            return MockK.anyValue(invocation.method.returnType) {
                invocation.self.childMockK(invocation)
            }
        }

        override fun answer(answer: Answer<*>) {
            var ans = answer
            while (calls.size != recordingStarted) {
                calls.removeAt(calls.size - 1).let {
                    it.invocation.self.addAnswer(
                            InvocationMatcher(
                                    EqMatcher(it.invocation.self),
                                    EqMatcher(it.invocation.method),
                                    it.matchers as List<Matcher<Any>>),
                            ans)
                    ans = ConstantAnswer(it.invocation.self)
                }
            }
            recordingStarted = -1
        }
    }

}


