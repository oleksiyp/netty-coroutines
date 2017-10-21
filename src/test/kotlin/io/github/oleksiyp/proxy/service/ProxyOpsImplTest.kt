package io.github.oleksiyp.proxy.service

import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.experimental.runBlocking
import mockk.*
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class ProxyOpsImplTest : StringSpec() {
    init {
        val ops = spyk<ProxyOpsImpl>()

        "on listen proxy ops should add a connection" {
            every { ops.newConnection(any(), any(), any()) } returns mockk<ProxyConnection>()

            runBlocking {
                ops.listen(3333, "host", 5555)
            }

            verify { ops.newConnection(3333, "host", 5555) }
        }

    }

}