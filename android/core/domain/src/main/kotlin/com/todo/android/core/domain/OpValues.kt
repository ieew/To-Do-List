package com.todo.android.core.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** op 值的读写helper；只做类型收敛，不改写 wire 值。 */
internal object OpValues {
    fun stringOrNull(value: JsonElement): String? {
        if (value is JsonNull) return null
        if (value is JsonPrimitive) return value.content
        return value.toString()
    }

    fun string(value: JsonElement, fallback: String): String = stringOrNull(value) ?: fallback

    fun boolOrNull(value: JsonElement): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) null else primitive.booleanOrNull
    }

    fun bool(value: JsonElement): Boolean = boolOrNull(value) ?: false

    fun doubleOrNull(value: JsonElement): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) null else primitive.doubleOrNull
    }

    fun double(value: JsonElement, fallback: Double): Double = doubleOrNull(value) ?: fallback

    /** 集合元素值：wire 规定为字符串 id；非字符串时退化为 JSON 文本。 */
    fun elementId(value: JsonElement): String = stringOrNull(value) ?: value.toString()

    /** 历史 diff 里的可读文本。 */
    fun displayText(value: JsonElement?): String = when {
        value == null || value is JsonNull -> "空"
        value is JsonPrimitive && value.booleanOrNull == true -> "是"
        value is JsonPrimitive && value.booleanOrNull == false -> "否"
        else -> stringOrNull(value) ?: value.toString()
    }
}
