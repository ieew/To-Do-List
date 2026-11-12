package com.todo.android.core.network.di

import com.todo.android.core.network.BaseUrlInterceptor
import com.todo.android.core.network.JsonConverterFactory
import com.todo.android.core.network.ServerConfig
import com.todo.android.core.network.api.AuthApi
import com.todo.android.core.network.api.MemberApi
import com.todo.android.core.network.api.SyncApi
import com.todo.android.core.network.session.AuthInterceptor
import com.todo.android.core.network.session.KeystoreTokenCipher
import com.todo.android.core.network.session.TokenAuthenticator
import com.todo.android.core.network.session.TokenCipher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // 协议要求拒绝未知字段，避免静默丢数据。
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        baseUrlInterceptor: BaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(JsonConverterFactory(json))
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideMemberApi(retrofit: Retrofit): MemberApi = retrofit.create(MemberApi::class.java)

    @Provides
    @Singleton
    fun provideServerConfig(): ServerConfig = ServerConfig()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindings {
    @Binds
    @Singleton
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher
}
