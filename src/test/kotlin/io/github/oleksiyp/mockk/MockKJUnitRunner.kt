package io.github.oleksiyp.mockk

import javassist.Loader
import org.junit.runner.Description
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import java.lang.Thread.currentThread

class MockKJUnitRunner(cls: Class<*>) : Runner() {

    val pool = TranslatingClassPool(MockKTranslator())
    val loader = Loader(currentThread().contextClassLoader, pool)

    init {
        loader.delegateLoadingOf("org.junit.runner.")
        currentThread().contextClassLoader = loader
    }

    val parentRunner = ParentRunnerFinderDynamicFinder(cls) { loader.loadClass(it.name) }.runner

    override fun run(notifier: RunNotifier?) {
        parentRunner.run(notifier)
    }

    override fun getDescription(): Description = parentRunner.description
}

