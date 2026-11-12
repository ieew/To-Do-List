package com.todo.android.core.network.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.todo.android.core.network.BaseUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 登录态。令牌只存 Keystore 加密后的 DataStore，不进 SQLite / 日志 / 备份。 */
@Serializable
data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val userId: String,
    val username: String,
    val displayName: String? = null,
    val serverUrl: String = BaseUrl.DEFAULT,
    val deviceId: String = "",
)

interface TokenCipher {
    fun encrypt(plain: String): String

    fun decrypt(payload: String): String?
}

/** Android Keystore AES/GCM；每次加密使用随机 IV。 */
class KeystoreTokenCipher @Inject constructor() : TokenCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(payload, Base64.NO_WRAP)
        return "$iv:$body"
    }

    override fun decrypt(payload: String): String? = runCatching {
        val (ivPart, bodyPart) = payload.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivPart, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(bodyPart, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "com.todo.android.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

private val Context.sessionDataStore by preferencesDataStore(name = "todo_session")

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: TokenCipher,
    private val json: Json,
) {
    private val sessionKey = stringPreferencesKey("session")

    val session: Flow<SessionTokens?> = context.sessionDataStore.data.map { prefs ->
        prefs[sessionKey]?.let(::decode)
    }

    suspend fun current(): SessionTokens? = session.first()

    suspend fun save(tokens: SessionTokens) {
        val encrypted = cipher.encrypt(json.encodeToString(SessionTokens.serializer(), tokens))
        context.sessionDataStore.edit { it[sessionKey] = encrypted }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(sessionKey) }
    }

    private fun decode(encrypted: String): SessionTokens? = runCatching {
        val plain = cipher.decrypt(encrypted) ?: return null
        json.decodeFromString(SessionTokens.serializer(), plain)
    }.getOrNull()
}
