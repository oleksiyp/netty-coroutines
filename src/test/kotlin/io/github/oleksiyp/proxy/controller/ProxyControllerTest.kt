package io.github.oleksiyp.proxy.controller

import io.github.oleksiyp.netty.JsonScope
import io.github.oleksiyp.netty.RequestHttpHandlerScope
import io.github.oleksiyp.proxy.service.ProxyConnection
import io.github.oleksiyp.proxy.service.ProxyOps
import io.kotlintest.experimental.mockk.MockKJUnitRunner
import io.kotlintest.experimental.mockk.every
import io.kotlintest.experimental.mockk.mockk
import io.kotlintest.experimental.mockk.verify
import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.experimental.runBlocking
import org.junit.runner.RunWith
import java.lang.StringBuilder
import kotlin.jvm.functions.Function1

@RunWith(MockKJUnitRunner::class)
class ProxyControllerTest : StringSpec() {
    init {
        val ops = mockk<ProxyOps>()
        val scope = mockk<RequestHttpHandlerScope>()
        val controller = ProxyController(ops)

        "httpHandler for /proxy/listen/PORT should return response" {
            every { scope.params.path() } returns "/proxy/listen/555"
            every { scope.request.method() } returns HttpMethod.GET
            every { scope.params } returns QueryStringDecoder("/proxy/listen/555?host=host&port=333")
            every { ops.listen(555, "host", 333) } answers { nothing }
            every { scope.response(any<String>()) } answers { nothing }

            runBlocking {
                controller.httpHandler(scope)
            }

            verify { scope.response("Done") }
        }

        "httpHandler for /proxy/all should return response" {
            every { scope.params.path() } returns "/proxy/all"
            every { scope.request.method() } returns HttpMethod.GET

            val conn = mockk<ProxyConnection>()

            every { ops.allConnections() } returns listOf(conn)
            every { conn.connectHost } returns "host"
            every { conn.connectPort } returns 555
            every { conn.listenPort } returns 333

            val strBuilder = StringBuilder()
            val jsonScope = JsonScope(strBuilder)
            every { scope.jsonResponse(captureLambda(Function1::class.java)) } answers { lambda(jsonScope) }

            runBlocking {
                controller.httpHandler(scope)
            }

            strBuilder.toString() shouldBe "[{\"listenPort\":333,\"connectHost\":\"host\",\"connectPort\":555}]"
            verify { scope.jsonResponse(any()) }
            verify { ops.allConnections() }
        }

        "httpHandler for /proxy/stop/PORT should return response" {
            every { scope.params.path() } returns "/proxy/stop/555"
            every { scope.request.method() } returns HttpMethod.GET
            every { ops.unlisten(555) } answers { nothing }
            every { scope.response(any<String>()) } answers { nothing }

            runBlocking {
                controller.httpHandler(scope)
            }

            verify {
                ops.unlisten(any())
                scope.response("Stopped")
            }
        }

        "httpHandler for /proxy/PORT/log should return response" {
            every { scope.params.path() } returns "/proxy/555/log"
            every { scope.request.method() } returns HttpMethod.GET
            every { ops.getConnection(555) } returns null
            every { scope.response(any<String>()) } returns null

            runBlocking {
                controller.httpHandler(scope)
            }

            verify {
                scope.response(match {
                    it!!.startsWith("<html>") &&
                            it.contains("/proxy/555/log")
                }, status = HttpResponseStatus.OK)
            }
        }
    }

}

