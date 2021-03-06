package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.mockk.MockKJUnitRunner
import io.github.oleksiyp.mockk.every
import io.github.oleksiyp.mockk.mockk
import io.github.oleksiyp.mockk.verify
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.proxy.service.ProxyOps
import io.kotlintest.specs.StringSpec
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.experimental.runBlocking
import org.junit.runner.RunWith

@RunWith(MockKJUnitRunner::class)
class ProxyControllerTest : StringSpec() {
    init {
        val ops = mockk<ProxyOps>()
        val scope = mockk<RequestHttpHandlerScope>()
        val controller = ProxyController(ops)

        "httpHandler for /proxy/PORT/log should return response" {
            every { scope.params.path() } returns "/proxy/555/log"
            every { scope.request.method() } returns HttpMethod.GET

            runBlocking {
                controller.httpHandler(scope)
            }

            verify {
                scope.response(match {
                    it.startsWith("<html>") &&
                            it.contains("/proxy/555/log")
                }, status = HttpResponseStatus.OK)
            }
        }
    }

}

