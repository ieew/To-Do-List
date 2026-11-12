package com.todo.android.core.network.api

import com.todo.android.core.network.wire.InviteRequest
import com.todo.android.core.network.wire.LoginRequest
import com.todo.android.core.network.wire.LoginResponse
import com.todo.android.core.network.wire.LogoutRequest
import com.todo.android.core.network.wire.MemberMutationResponse
import com.todo.android.core.network.wire.MembersResponse
import com.todo.android.core.network.wire.PullResponse
import com.todo.android.core.network.wire.PushRequest
import com.todo.android.core.network.wire.PushResponse
import com.todo.android.core.network.wire.RefreshRequest
import com.todo.android.core.network.wire.RefreshResponse
import com.todo.android.core.network.wire.RegisterRequest
import com.todo.android.core.network.wire.RegisterResponse
import com.todo.android.core.network.wire.RemoveMemberRequest
import com.todo.android.core.network.wire.UserOut
import com.todo.android.core.network.wire.WireRoleRequest
import com.todo.android.core.network.wire.WorkspaceOut
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class MeResponse(val user: UserOut)

@Serializable
data class WorkspacesResponse(val workspaces: List<WorkspaceOut> = emptyList())

/** 路径不带前导斜杠：baseUrl 由 `BaseUrlInterceptor` 重写到设置中的服务器地址。 */
interface AuthApi {
    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): RegisterResponse

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest)

    @GET("v1/auth/me")
    suspend fun me(): MeResponse

    @GET("v1/workspaces")
    suspend fun workspaces(): WorkspacesResponse
}

interface SyncApi {
    @POST("v1/sync/push")
    suspend fun push(@Body body: PushRequest): PushResponse

    @GET("v1/sync/pull")
    suspend fun pull(
        @Query("since_seq") sinceSeq: Long,
        @Query("limit") limit: Int = 200,
    ): PullResponse
}

/** 工作区成员：列表 / 邀请 / 改角色 / 移除（服务端代为生成 membership op）。 */
interface MemberApi {
    @GET("v1/workspace/members")
    suspend fun members(@Query("workspace_id") workspaceId: String): MembersResponse

    @POST("v1/workspace/invite")
    suspend fun invite(@Body body: InviteRequest): MemberMutationResponse

    @PATCH("v1/workspace/role")
    suspend fun setRole(@Body body: WireRoleRequest): MemberMutationResponse

    @POST("v1/workspace/remove")
    suspend fun remove(@Body body: RemoveMemberRequest)
}
