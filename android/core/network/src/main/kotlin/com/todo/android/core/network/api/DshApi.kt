package com.todo.android.core.network.api

import com.todo.android.core.network.wire.DshAckRequest
import com.todo.android.core.network.wire.DshAckResponse
import com.todo.android.core.network.wire.DshCredentialResponse
import com.todo.android.core.network.wire.DshPullResponse
import com.todo.android.core.network.wire.DshRefreshRequest
import com.todo.android.core.network.wire.DshRefreshResponse
import com.todo.android.core.network.wire.DshRegisterRequest
import com.todo.android.core.network.wire.DshRegisterResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** dsh 数据通道：App 用账号 Bearer 注册/签发凭证，设备用凭证续期。 */
interface DshApi {
    @POST("v1/dsh/devices/register")
    suspend fun register(@Body body: DshRegisterRequest): DshRegisterResponse

    @POST("v1/dsh/devices/{deviceId}/credential")
    suspend fun issueCredential(@Path("deviceId") deviceId: String): DshCredentialResponse

    @POST("v1/dsh/devices/refresh")
    suspend fun refresh(@Body body: DshRefreshRequest): DshRefreshResponse

    @POST("v1/dsh/devices/{deviceId}/revoke")
    suspend fun revoke(@Path("deviceId") deviceId: String): JsonElement

    @GET("v1/dsh/pull")
    suspend fun pull(
        @Query("device_id") deviceId: String,
        @Query("since") since: Long = 0,
        @Query("limit") limit: Int = 100,
    ): DshPullResponse

    @POST("v1/dsh/ack")
    suspend fun ack(@Body body: DshAckRequest): DshAckResponse
}
