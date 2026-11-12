package com.todo.android.core.network

import java.lang.reflect.Type
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit

private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

/** 用 kotlinx.serialization 实现 Retrofit 转换器，避免额外依赖 Moshi。 */
class JsonConverterFactory(private val json: Json) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        @Suppress("UNCHECKED_CAST")
        val strategy = json.serializersModule.serializer(type) as DeserializationStrategy<Any>
        return Converter<ResponseBody, Any> { body -> json.decodeFromString(strategy, body.string()) }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        @Suppress("UNCHECKED_CAST")
        val strategy = json.serializersModule.serializer(type) as SerializationStrategy<Any>
        return Converter<Any, RequestBody> { value ->
            json.encodeToString(strategy, value).toRequestBody(JSON_MEDIA_TYPE)
        }
    }
}
