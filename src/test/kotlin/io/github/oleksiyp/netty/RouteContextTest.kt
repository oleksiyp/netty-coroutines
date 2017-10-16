package io.github.oleksiyp.netty

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

class RouteContextTest : StringSpec() {
    init {
        "length should return size of string" {
            "hello".length shouldBe 5
        }
    }
}