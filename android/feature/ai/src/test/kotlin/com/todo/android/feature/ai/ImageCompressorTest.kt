package com.todo.android.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 图片降采样与缩放的纯计算（与 Windows 端 1568px 上限保持一致）。 */
class ImageCompressorTest {

    @Test
    fun sampleSizeKeepsLongestEdgeAboveHalfLimit() {
        // 4000 长边：4000/2=2000 >= 1568 → sample=2，剩 2000/2=1000 < 1568 停止
        assertEquals(2, ImageCompressor.calculateInSampleSize(4000, 3000))
        // 6000 长边：6000/2=3000 >= 1568 → sample=2，剩 3000/2=1500 < 1568 停止（再由 scaleToEdge 精确缩放）
        assertEquals(2, ImageCompressor.calculateInSampleSize(6000, 4000))
        // 12000 长边：12000→6000→3000 都还 >= 1568，sample=4
        assertEquals(4, ImageCompressor.calculateInSampleSize(12000, 9000))
        // 已经小于上限
        assertEquals(1, ImageCompressor.calculateInSampleSize(1200, 900))
        // 刚好等于上限
        assertEquals(1, ImageCompressor.calculateInSampleSize(1568, 1000))
    }

    @Test
    fun sampleSizeHandlesInvalidDimensions() {
        assertEquals(1, ImageCompressor.calculateInSampleSize(0, 0))
        assertEquals(1, ImageCompressor.calculateInSampleSize(-10, 20))
    }

    @Test
    fun scaleKeepsAspectRatio() {
        val (w, h) = ImageCompressor.scaleToEdge(4000, 3000)
        assertEquals(1568, w)
        assertEquals(1176, h)
    }

    @Test
    fun scaleDoesNotUpscaleSmallImage() {
        assertEquals(800 to 600, ImageCompressor.scaleToEdge(800, 600))
    }

    @Test
    fun scaleHandlesPortrait() {
        val (w, h) = ImageCompressor.scaleToEdge(1000, 4000)
        assertEquals(1568, h)
        assertEquals(392, w)
    }
}
