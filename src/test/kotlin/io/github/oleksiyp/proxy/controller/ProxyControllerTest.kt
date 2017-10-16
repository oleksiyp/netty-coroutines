package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.mockk.MockKJUnitRunner
import io.github.oleksiyp.mockk.mockk
import io.github.oleksiyp.mockk.on
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.proxy.service.ProxyOps
import io.kotlintest.specs.StringSpec
import io.netty.handler.codec.http.HttpMethod
import kotlinx.coroutines.experimental.runBlocking
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class ProxyControllerTest : StringSpec() {
    init {
        val ops = mockk<ProxyOps>()
        val scope = mockk<RequestHttpHandlerScope>()
        val controller = ProxyController(ops)

        on { scope.params.path() } doReturn "/proxy/all"
        on { scope.request.method() } doReturn HttpMethod.GET

        "bla bla" {
            runBlocking {
                controller.httpHandler(scope)

//                verify(scope).response(eq("abc"), eq("text/html"), any(), eq(OK))
            }
        }
    }
}

