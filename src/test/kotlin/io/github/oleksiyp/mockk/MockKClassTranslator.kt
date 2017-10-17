package io.github.oleksiyp.mockk

import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.bytecode.AccessFlag
import java.lang.reflect.Modifier
import java.util.Collections.synchronizedSet

class MockKClassTranslator {
    lateinit var noArgsParamType : CtClass

    fun start(pool: ClassPool) {
        noArgsParamType = pool.makeClass("\$NoArgsConstructorParamType")
    }

    val load = synchronizedSet(hashSetOf<String>())

    fun onLoad(cls: CtClass) {
        if (!load.add(cls.name) || cls.isFrozen) {
            return
        }
        removeFinal(cls)
        addNoArgsConstructor(cls)
    }

    private fun addNoArgsConstructor(cls: CtClass) {
        if (cls.isAnnotation || cls.isArray || cls.isEnum || cls.isInterface) {
            return
        }

        if (cls.constructors.any { isNoArgsConstructor(it) }) {
            return
        }

        if (cls.superclass == null) {
            return
        }

        with(cls.superclass) {
            when {
                constructors.any { isNoArgsConstructor(it) } -> {
                    if (cls.constructors.any { isNoArgsConstructor(it) }) {
                        return@with
                    }

                    val newConstructor = CtConstructor(arrayOf(noArgsParamType), cls)
                    cls.addConstructor(newConstructor)
                    newConstructor.setBody("super($1);")
                }
                constructors.any { it.parameterTypes.isEmpty() } -> {
                    if (cls.constructors.any { isNoArgsConstructor(it) }) {
                        return@with
                    }

                    val newConstructor = CtConstructor(arrayOf(noArgsParamType), cls)
                    cls.addConstructor(newConstructor)
                    newConstructor.setBody("super();")
                }
                else -> println("No constructor")
            }
        }
    }

    private fun isNoArgsConstructor(it: CtConstructor) =
            it.parameterTypes.size == 1 && it.parameterTypes[0] == noArgsParamType

//    private fun isStaticOrNotPublic(className: String, defaultClass: CtClass): Boolean {
//        return className.endsWith("Test") || className.endsWith("TestRunner") ||
//                Modifier.isStatic(defaultClass.modifiers) ||
//                !Modifier.isPublic(defaultClass.modifiers)
//    }

    fun removeFinal(clazz: CtClass) {
        removeFinalOnClass(clazz)
        removeFinalOnMethods(clazz)
        clazz.stopPruning(true)
    }

    private fun removeFinalOnMethods(clazz: CtClass) {
        clazz.declaredMethods.forEach {
            if (Modifier.isFinal(it.modifiers)) {
                it.modifiers = javassist.Modifier.clear(it.modifiers, Modifier.FINAL)
            }
        }
    }


    private fun removeFinalOnClass(clazz: CtClass) {
        val modifiers = clazz.modifiers
        if (Modifier.isFinal(modifiers)) {

            clazz.classFile2.accessFlags = AccessFlag.of(javassist.Modifier.clear(modifiers, Modifier.FINAL))
        }
    }

}