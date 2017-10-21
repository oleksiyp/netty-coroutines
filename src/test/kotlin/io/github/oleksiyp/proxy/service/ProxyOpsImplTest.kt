package io.github.oleksiyp.proxy.service

import io.kotlintest.experimental.mockk.*
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.experimental.runBlocking
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

        "on unlisten proxy ops should get a connection and stop it" {
            every { ops.getConnection(3333).stop() } answers { nothing }

            runBlocking {
                ops.unlisten(3333)
            }

            verify { ops.getConnection(3333).stop() }
        }
    }

}