package com.todo.android.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 同步状态：pending / synced / rejected。 */
object OpSyncState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * op 日志是唯一事实来源：没有业务表，投影从 op_log 现算。
 * 禁止物理删除行；删除实体只能写 `__deleted=true` 墓碑。
 */
@Entity(
    tableName = "op_log",
    indices = [
        Index(value = ["op_id"], unique = true),
        Index(value = ["sync_state", "created_at"]),
        Index(value = ["workspace_id", "server_seq"]),
        Index(
            value = ["entity_type", "entity_id", "physical_ms", "counter", "node_id", "op_id"],
        ),
    ],
)
data class OpLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "row_id") val rowId: Long = 0,
    @ColumnInfo(name = "op_id") val opId: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "operation") val operation: String,
    @ColumnInfo(name = "field") val field: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "physical_ms") val physicalMs: Long,
    @ColumnInfo(name = "counter") val counter: Long,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: String = OpSyncState.PENDING,
    @ColumnInfo(name = "server_seq") val serverSeq: Long? = null,
    @ColumnInfo(name = "sync_error") val syncError: String? = null,
)

/** 通用键值元数据：sync_cursor / sync_account / device_id 等。 */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** 用户偏好（主题、活动工作区等），值为 JSON 文本。 */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** 设备本地偏好（当前用户、便利贴布局等），不参与同步。 */
@Entity(tableName = "local_prefs")
data class LocalPrefEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

/** 提醒本地态：last_fired_at 防止重复补发，snooze_until 支持稍后提醒。 */
@Entity(tableName = "task_local")
data class TaskLocalEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long? = null,
    @ColumnInfo(name = "snooze_until") val snoozeUntil: Long? = null,
)

/** 本地 HLC 单行表（id 固定为 1）。 */
@Entity(tableName = "local_hlc")
data class LocalHlcEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long = 1,
    @ColumnInfo(name = "physical_ms") val physicalMs: Long,
    @ColumnInfo(name = "counter") val counter: Long,
    @ColumnInfo(name = "node_id") val nodeId: String,
)
