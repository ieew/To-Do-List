package com.todo.android.core.data.backup

import android.content.Context
import android.net.Uri
import com.todo.android.core.data.repository.ImportResult
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.time.IsoTime
import com.todo.android.core.domain.EntityType
import com.todo.android.core.domain.Hlc
import com.todo.android.core.domain.Op
import com.todo.android.core.domain.OpOperation
import com.todo.android.core.domain.OpSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class BackupSummary(
    val fileName: String,
    val workspaces: Int = 0,
    val lists: Int = 0,
    val tags: Int = 0,
    val tasks: Int = 0,
    val ops: Int = 0,
)

data class ImportPreview(
    val summary: BackupSummary,
    val ops: List<Op>,
)

@Serializable
private data class BackupManifest(
    val format: String = "todo-backup",
    val version: Int = 1,
    @SerialName("created_at") val createdAt: String,
    val counts: BackupCounts,
)

@Serializable
private data class BackupCounts(
    val workspaces: Int,
    val lists: Int,
    val tags: Int,
    val tasks: Int,
    val ops: Int,
)

@Serializable
private data class BackupOp(
    @SerialName("op_id") val opId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("device_id") val deviceId: String,
    val operation: String,
    val field: String,
    val value: JsonElement,
    val hlc: BackupHlc,
    val source: String,
    @SerialName("source_meta") val sourceMeta: BackupSourceMeta? = null,
)

@Serializable
private data class BackupHlc(
    @SerialName("physical_ms") val physicalMs: Long,
    val counter: Long,
    @SerialName("node_id") val nodeId: String,
)

@Serializable
private data class BackupSourceMeta(val model: String? = null, @SerialName("prompt_hash") val promptHash: String? = null)

/** 导出/导入：zip 内含 manifest.json、workspace/ops.jsonl；不含密钥、令牌与设备偏好。 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OpRepository,
    private val json: Json,
) {
    suspend fun export(uri: Uri, fileName: String = "todo-backup.zip"): BackupSummary = withContext(Dispatchers.IO) {
        val ops = repository.loadAllOps()
        val projection = repository.observeProjection().first()
        val counts = BackupCounts(
            workspaces = projection.workspaces.size,
            lists = projection.lists.size,
            tags = projection.tags.size,
            tasks = projection.tasks.size,
            ops = ops.size,
        )
        val manifest = BackupManifest(createdAt = IsoTime.nowIso(), counts = counts)

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("workspace/ops.jsonl"))
                ops.forEach { op ->
                    zip.write((json.encodeToString(BackupOp.serializer(), op.toBackup()) + "\n").toByteArray())
                }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("workspace/projection.json"))
                zip.write(
                    json.encodeToString(
                        BackupCounts.serializer(),
                        counts,
                    ).toByteArray(),
                )
                zip.closeEntry()
            }
        }
        BackupSummary(fileName, counts.workspaces, counts.lists, counts.tags, counts.tasks, counts.ops)
    }

    suspend fun inspect(uri: Uri, fileName: String = "todo-backup.zip"): ImportPreview? = withContext(Dispatchers.IO) {
        var ops: List<Op> = emptyList()
        var summary = BackupSummary(fileName)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        val manifest = json.decodeFromString(
                            BackupManifest.serializer(),
                            zip.readEntryText(),
                        )
                        summary = BackupSummary(
                            fileName = fileName,
                            workspaces = manifest.counts.workspaces,
                            lists = manifest.counts.lists,
                            tags = manifest.counts.tags,
                            tasks = manifest.counts.tasks,
                            ops = manifest.counts.ops,
                        )
                    } else if (name == "workspace/ops.jsonl") {
                        ops = zip.readEntryText()
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                runCatching {
                                    json.decodeFromString(BackupOp.serializer(), line).toDomain()
                                }.getOrNull()
                            }
                            .toList()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (ops.isEmpty()) null else ImportPreview(summary, ops)
    }

    suspend fun import(preview: ImportPreview): ImportResult =
        repository.importOps(preview.ops.map { it.copy(source = OpSource.IMPORT) })

    private fun Op.toBackup(): BackupOp = BackupOp(
        opId = opId,
        workspaceId = workspaceId,
        entityType = entityType.wire,
        entityId = entityId,
        actorId = actorId,
        deviceId = deviceId,
        operation = operation.wire,
        field = field,
        value = value,
        hlc = BackupHlc(hlc.physicalMs, hlc.counter, hlc.nodeId),
        source = source.wire,
        sourceMeta = BackupSourceMeta(model, null),
    )

    private fun BackupOp.toDomain(): Op? {
        val type = EntityType.fromWire(entityType) ?: return null
        val op = OpOperation.fromWire(operation) ?: return null
        return Op(
            opId = opId,
            workspaceId = workspaceId,
            entityType = type,
            entityId = entityId,
            actorId = actorId,
            deviceId = deviceId,
            operation = op,
            field = field,
            value = value,
            hlc = Hlc(hlc.physicalMs, hlc.counter, hlc.nodeId),
            source = OpSource.fromWire(source) ?: OpSource.HUMAN,
            model = sourceMeta?.model,
        )
    }

    private fun ZipInputStream.readEntryText(): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }
}
