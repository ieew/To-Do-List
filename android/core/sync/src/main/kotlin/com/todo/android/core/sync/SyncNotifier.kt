package com.todo.android.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 同步触发契约：前台走共享流即时触发，后台排 OneTimeWork。 */
interface SyncNotifier {
    val foregroundRequests: SharedFlow<Unit>

    fun requestSync()

    /** 前台可见时直接发共享流，后台走 OneTimeWork。 */
    fun setForeground(active: Boolean)
}

@Singleton
class SyncNotifierImpl @Inject constructor(
    private val scheduler: SyncScheduler,
) : SyncNotifier, com.todo.android.core.data.repository.SyncTrigger {
    private val _foregroundRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    override val foregroundRequests: SharedFlow<Unit> = _foregroundRequests.asSharedFlow()

    @Volatile
    private var foregroundActive = false

    override fun setForeground(active: Boolean) {
        foregroundActive = active
    }

    override fun requestSync() {
        if (foregroundActive) {
            _foregroundRequests.tryEmit(Unit)
        } else {
            scheduler.enqueueOneTime()
        }
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueueOneTime() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * 周期兜底。注意不设 `NetworkType.CONNECTED`：模拟器/内网常只有
     * PARTIAL_CONNECTIVITY，缺少 VALIDATED 能力会让 Worker 永不执行；
     * 离线由 SyncEngine 标记 offline / retrying。
     */
    fun schedulePeriodic(minutes: Int = DEFAULT_INTERVAL_MINUTES) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val ONE_TIME = "todo-sync-now"
        const val PERIODIC = "todo-sync-periodic"
        const val DEFAULT_INTERVAL_MINUTES = 15
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val state = engine.runOnce()
        return when (state.status) {
            SyncStatus.OFFLINE, SyncStatus.RETRYING -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Hilt 的 WorkManager 配置钩子，由 Application 实现。 */
fun workManagerConfiguration(factory: androidx.hilt.work.HiltWorkerFactory): Configuration =
    Configuration.Builder()
        .setWorkerFactory(factory)
        .build()
