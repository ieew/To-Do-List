package com.todo.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlTest {
    @Test
    fun `保留默认地址`() {
        assertEquals(BaseUrl.DEFAULT, BaseUrl.normalize(null))
        assertEquals(BaseUrl.DEFAULT, BaseUrl.normalize("  "))
        assertEquals(BaseUrl.DEFAULT, BaseUrl.normalize("http://127.0.0.1:8000"))
    }

    @Test
    fun `去掉尾部斜杠与 v1 前缀`() {
        assertEquals("http://127.0.0.1:8000", BaseUrl.normalize("http://127.0.0.1:8000/"))
        assertEquals("http://127.0.0.1:8000", BaseUrl.normalize("http://127.0.0.1:8000/v1"))
        assertEquals("http://127.0.0.1:8000", BaseUrl.normalize("http://127.0.0.1:8000/v1/"))
        assertEquals("http://10.0.2.2:8011", BaseUrl.normalize("http://10.0.2.2:8011/v1/"))
    }

    @Test
    fun `缺少协议时补全 http`() {
        assertEquals("http://192.168.1.8:8000", BaseUrl.normalize("192.168.1.8:8000"))
    }

    @Test
    fun `接口路径与归一化基地址拼接不会产生 v1 重复`() {
        val base = BaseUrl.toHttpUrl("http://127.0.0.1:8000/v1")
        val url = base!!.newBuilder().addPathSegments("v1/sync/push").build()
        assertEquals("/v1/sync/push", url.encodedPath)
    }
}
