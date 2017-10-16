package io.github.oleksiyp.mockk;

import javassist.ClassPool
import javassist.CtClass

public class TranslatingClassPool(
        private val mockKTranslator: MockKTranslator)
    : ClassPool() {

    init {
        appendSystemPath()
        mockKTranslator.start(this)
    }

    override fun get0(classname: String, useCache: Boolean): CtClass {
        val cls = super.get0(classname, useCache)
        mockKTranslator.onLoad(cls)
        return cls
    }
}

