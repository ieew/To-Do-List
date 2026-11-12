package com.todo.android.core.data.settings

import com.todo.android.core.data.db.PreferenceDao
import com.todo.android.core.data.db.PreferenceEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

@Singleton
class SettingsStore @Inject constructor(
    private val dao: PreferenceDao,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    @Volatile
    private var cached: AppSettings = AppSettings()

    /** 供 ViewModel 在非挂起逻辑里读取当前值（缓存由 observe/current/update 维护）。 */
    fun currentSync(): AppSettings = cached

    fun observe(): Flow<AppSettings> = dao.observeAll().map { rows ->
        decode(rows.firstOrNull { it.key == KEY }?.value).also { cached = it }
    }

    suspend fun current(): AppSettings = decode(dao.get(KEY)).also { cached = it }

    /** 读-改-写需要串行化，避免并发保存互相覆盖。 */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val next = transform(decode(dao.get(KEY)))
        dao.put(PreferenceEntity(KEY, json.encodeToString(AppSettings.serializer(), next)))
        cached = next
        next
    }

    suspend fun save(settings: AppSettings) {
        mutex.withLock {
            dao.put(PreferenceEntity(KEY, json.encodeToString(AppSettings.serializer(), settings)))
            cached = settings
        }
    }

    private fun decode(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
            .getOrElse { AppSettings() }
    }

    companion object {
        const val KEY = "app_settings"
    }
}
