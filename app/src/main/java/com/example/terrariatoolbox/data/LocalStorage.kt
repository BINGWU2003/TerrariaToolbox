package com.example.terrariatoolbox.data

import android.util.AtomicFile
import com.example.terrariatoolbox.domain.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

fun interface AtomicWriter { fun write(file: File, bytes: ByteArray) }

class AndroidAtomicWriter : AtomicWriter {
    override fun write(file: File, bytes: ByteArray) {
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw ToolException("无法创建内部备份目录，请检查空间。") }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        if (!atomic.readFully().contentEquals(bytes)) throw ToolException("内部存储校验失败，请检查剩余空间。")
    }
}

internal fun readAtomic(file: File): ByteArray = if (File(file.path + ".bak").exists()) {
    // AtomicFile's backup, if present, is the last committed version.
    File(file.path + ".bak").readBytes()
} else file.readBytes()

internal fun existsAtomic(file: File): Boolean = file.exists() || File(file.path + ".bak").exists()

internal fun SourceInfo.toJson(): JsonObject = buildJsonObject {
    put("uri", uri); put("grantUri", grantUri); put("identity", identity); put("authority", authority)
    put("documentId", documentId); put("displayName", displayName); put("selection", selection)
    put("writable", writable); put("persisted", persisted); put("knownTarget", knownTarget); put("confirmed", confirmed)
    lastVerifiedAt?.let { put("lastVerifiedAt", it) }
    put("accessMethod", accessMethod.name)
}

internal fun JsonObject.source(): SourceInfo = SourceInfo(
    string("uri"), string("grantUri"), string("identity"), string("authority"), string("documentId"),
    string("displayName"), string("selection"), bool("writable"), bool("persisted"), bool("knownTarget"),
    bool("confirmed"), (get("lastVerifiedAt") as? JsonPrimitive)?.longOrNull,
    (get("accessMethod") as? JsonPrimitive)?.content?.let(AccessMethod::valueOf) ?: AccessMethod.SAF,
)
internal fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content
internal fun JsonObject.bool(key: String): Boolean = (getValue(key) as JsonPrimitive).boolean
internal fun JsonObject.long(key: String): Long = (getValue(key) as JsonPrimitive).long
internal fun parseObject(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
internal fun JsonObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

class FileBackupRepository(
    private val directory: File,
    private val writer: AtomicWriter,
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupRepository {
    override fun save(source: SourceInfo, bytes: ByteArray, reason: String, config: ConfigDocument?): BackupRecord {
        try {
            val record = BackupRecord(UUID.randomUUID().toString(), clock(), source, bytes.size.toLong(),
                contentHash(bytes), config?.languageNumber, config?.version, reason)
            writer.write(dataFile(record.id), bytes)
            if (contentHash(readAtomic(dataFile(record.id))) != record.hash) throw ToolException("原始备份回读校验失败。")
            // Metadata is committed last: an orphan .bin is never presented as a valid backup.
            writer.write(metaFile(record.id), record.toJson().bytes())
            read(record)
            return record
        } catch (error: ToolException) { throw error }
        catch (_: Exception) { throw ToolException("备份保存失败，已中止操作，请检查剩余空间。") }
    }

    override fun read(record: BackupRecord): ByteArray {
        try {
            val stored = parseRecord(readAtomic(metaFile(record.id)))
            if (stored != record) throw ToolException("备份记录不一致，已中止操作。")
            val bytes = readAtomic(dataFile(record.id))
            if (bytes.size.toLong() != record.size || contentHash(bytes) != record.hash) {
                throw ToolException("原始备份损坏或不完整，禁止恢复或导出。")
            }
            return bytes
        } catch (error: ToolException) { throw error }
        catch (_: Exception) { throw ToolException("无法读取完整备份，禁止恢复或导出。") }
    }

    override fun list(): List<BackupRecord> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }.mapNotNull {
            runCatching { parseRecord(readAtomic(it)) }.getOrNull()
        }.sortedByDescending { it.createdAt }

    private fun dataFile(id: String) = File(directory, safeId(id) + ".bin")
    private fun metaFile(id: String) = File(directory, safeId(id) + ".json")
    private fun safeId(id: String): String = UUID.fromString(id).toString().also {
        if (it != id) throw ToolException("备份标识无效。")
    }
    private fun BackupRecord.toJson(): JsonObject = buildJsonObject {
        put("id", id); put("createdAt", createdAt); put("source", source.toJson()); put("size", size)
        put("hash", hash); put("reason", reason); put("tool", tool); put("fileType", fileType)
        language?.let { put("language", it) }; gameVersion?.let { put("gameVersion", it) }
    }
    private fun parseRecord(bytes: ByteArray): BackupRecord = parseObject(bytes).let {
        BackupRecord(it.string("id"), it.long("createdAt"), it.getValue("source").jsonObject.source(),
            it.long("size"), it.string("hash"), (it["language"] as? JsonPrimitive)?.content,
            (it["gameVersion"] as? JsonPrimitive)?.content, it.string("reason"), it.string("tool"), it.string("fileType"))
    }
}

class FileOperationJournal(private val file: File, private val writer: AtomicWriter) : OperationJournal {
    override fun load(): PendingOperation? {
        if (!existsAtomic(file)) return null
        return try {
            parseObject(readAtomic(file)).let {
                if (it["complete"]?.jsonPrimitive?.booleanOrNull == true) null
                else PendingOperation(it.getValue("source").jsonObject.source(), it.string("backupId"),
                    it.string("beforeHash"), it.string("expectedHash"), it.string("kind"), it.long("startedAt"))
            }
        } catch (_: Exception) {
            throw ToolException("未完成操作记录无法读取。已禁止写入，请先导出原始备份检查。", true)
        }
    }
    override fun save(operation: PendingOperation) {
        try {
            writer.write(file, buildJsonObject {
                put("source", operation.source.toJson()); put("backupId", operation.backupId)
                put("beforeHash", operation.beforeHash); put("expectedHash", operation.expectedHash)
                put("kind", operation.kind); put("startedAt", operation.startedAt)
            }.bytes())
        } catch (_: Exception) { throw ToolException("无法保存操作记录，已中止写入。") }
    }
    override fun clear() {
        // A committed tombstone avoids losing a failed deletion or reviving an old .bak.
        writer.write(file, buildJsonObject { put("complete", true) }.bytes())
    }
}

data class EnvironmentNotes(val gameVersion: String = "", val mtVersion: String = "")

class SessionStore(private val directory: File, private val writer: AtomicWriter) {
    private val sourceFile get() = File(directory, "source.json")
    private val notesFile get() = File(directory, "environment.json")
    private val eventsFile get() = File(directory, "events.json")
    fun source(): SourceInfo? = if (existsAtomic(sourceFile)) parseObject(readAtomic(sourceFile)).source() else null
    fun saveSource(source: SourceInfo) = writer.write(sourceFile, source.toJson().bytes())
    fun notes(): EnvironmentNotes = if (existsAtomic(notesFile)) parseObject(readAtomic(notesFile)).let {
        EnvironmentNotes(it.string("gameVersion"), it.string("mtVersion"))
    } else EnvironmentNotes()
    fun saveNotes(notes: EnvironmentNotes) = writer.write(notesFile, buildJsonObject {
        put("gameVersion", notes.gameVersion.take(100)); put("mtVersion", notes.mtVersion.take(100))
    }.bytes())
    fun events(): List<String> = if (existsAtomic(eventsFile)) {
        Json.parseToJsonElement(readAtomic(eventsFile).toString(Charsets.UTF_8)).jsonArray.map { it.jsonPrimitive.content }
    } else emptyList()
    fun event(message: String) {
        val entries = (events() + "${System.currentTimeMillis()} | $message").takeLast(60)
        writer.write(eventsFile, JsonArray(entries.map { JsonPrimitive(it) }).toString().toByteArray())
    }
}
