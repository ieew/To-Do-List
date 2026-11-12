package com.todo.android.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OpLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: OpLogEntity): Long

    /** 参与投影的 op：过滤 rejected。 */
    @Query("SELECT * FROM op_log WHERE sync_state != 'rejected'")
    fun observeLive(): Flow<List<OpLogEntity>>

    @Query("SELECT * FROM op_log WHERE sync_state != 'rejected'")
    suspend fun loadLive(): List<OpLogEntity>

    @Query("SELECT * FROM op_log ORDER BY physical_ms, counter, node_id, op_id")
    suspend fun loadAll(): List<OpLogEntity>

    @Query("SELECT * FROM op_log WHERE sync_state = 'pending' ORDER BY created_at, row_id LIMIT :limit")
    suspend fun loadPending(limit: Int): List<OpLogEntity>

    @Query("SELECT COUNT(*) FROM op_log WHERE sync_state = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM op_log WHERE sync_state = 'rejected'")
    suspend fun rejectedCount(): Int

    @Query("SELECT op_id FROM op_log")
    suspend fun allOpIds(): List<String>

    @Query("UPDATE op_log SET sync_state = 'synced', server_seq = :seq, sync_error = NULL WHERE op_id = :opId")
    suspend fun markSynced(opId: String, seq: Long)

    /** 自己 push 出去的 op 若已存在，pull 到时只推进状态，不重复写入。 */
    @Query(
        "UPDATE op_log SET sync_state = 'synced', server_seq = :seq, sync_error = NULL " +
            "WHERE op_id = :opId AND sync_state = 'pending'",
    )
    suspend fun markSyncedIfPending(opId: String, seq: Long)

    @Query("UPDATE op_log SET sync_state = 'rejected', sync_error = :error WHERE op_id = :opId")
    suspend fun markRejected(opId: String, error: String)

    @Query("DELETE FROM op_log")
    suspend fun deleteAll()

    @Query("SELECT MAX(server_seq) FROM op_log WHERE server_seq IS NOT NULL")
    suspend fun maxServerSeq(): Long?
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MetaEntity)

    @Query("DELETE FROM meta WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM meta")
    suspend fun deleteAll()
}

@Dao
interface PreferenceDao {
    @Query("SELECT value FROM preferences WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM preferences WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT * FROM preferences")
    fun observeAll(): Flow<List<PreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PreferenceEntity)
}

@Dao
interface LocalPrefDao {
    @Query("SELECT value FROM local_prefs WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM local_prefs WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: LocalPrefEntity)
}

@Dao
interface TaskLocalDao {
    @Query("SELECT * FROM task_local WHERE task_id = :taskId")
    suspend fun get(taskId: String): TaskLocalEntity?

    @Query("SELECT * FROM task_local")
    suspend fun all(): List<TaskLocalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: TaskLocalEntity)
}

@Dao
interface LocalHlcDao {
    @Query("SELECT * FROM local_hlc WHERE id = 1")
    suspend fun get(): LocalHlcEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: LocalHlcEntity)
}
