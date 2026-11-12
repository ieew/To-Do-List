package com.todo.android.core.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.todo.android.core.network.session.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.aiDataStore by preferencesDataStore(name = "todo_ai_key")

/** AI 密钥：Keystore 加密后存 DataStore，只回显「已配置」，不进日志/备份。 */
@Singleton
class AiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: TokenCipher,
) {
    private val key = stringPreferencesKey("api_key")

    suspend fun hasKey(): Boolean = !context.aiDataStore.data.first()[key].isNullOrBlank()

    suspend fun read(): String? {
        val encrypted = context.aiDataStore.data.first()[key] ?: return null
        return cipher.decrypt(encrypted)
    }

    suspend fun save(apiKey: String) {
        val encrypted = cipher.encrypt(apiKey)
        context.aiDataStore.edit { it[key] = encrypted }
    }

    suspend fun clear() {
        context.aiDataStore.edit { it.remove(key) }
    }
}
