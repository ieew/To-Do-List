package com.todo.android.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 图片输入前的端上压缩，规则与 Windows 端一致：
 * 最长边 ≤ [MAX_EDGE]、JPEG 质量从 85 递减、编码后 ≤ [MAX_BYTES]。
 * 直接原图直传会让请求体过大，部分模型端点会直接 413。
 */
internal object ImageCompressor {
    const val MAX_EDGE = 1568
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_IMAGES = 5

    /** 解码时先用 2 的幂降采样，避免大图一次性占满内存。 */
    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** 等比缩放到最长边不超过 maxEdge；已够小则原样返回。 */
    fun scaleToEdge(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    /** 读取 → 降采样 → 缩放 → JPEG 压缩，返回 data URL；失败返回 null。 */
    fun encode(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        val (targetWidth, targetHeight) = scaleToEdge(decoded.width, decoded.height)
        val scaled = if (targetWidth == decoded.width && targetHeight == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }

        val buffer = ByteArrayOutputStream()
        var quality = 85
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
        while (buffer.size() > MAX_BYTES && quality > 40) {
            buffer.reset()
            quality -= 15
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
        }
        scaled.recycle()
        if (buffer.size() > MAX_BYTES) return null

        "data:image/jpeg;base64," + Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
