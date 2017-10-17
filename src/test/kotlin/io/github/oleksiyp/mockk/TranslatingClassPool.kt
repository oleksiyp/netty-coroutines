package io.github.oleksiyp.mockk;

import javassist.ClassPool
import javassist.CtClass

public class TranslatingClassPool(
        private val mockKClassTranslator: MockKClassTranslator)
    : ClassPool() {

    init {
        appendSystemPath()
        mockKClassTranslator.start(this)
    }

    override fun get0(classname: String, useCache: Boolean): CtClass {
        val cls = super.get0(classname, useCache)
        mockKClassTranslator.onLoad(cls)
        return cls
    }
}

