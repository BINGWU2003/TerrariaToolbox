package io.github.bingwu2003.terrariatoolbox.data

import io.github.bingwu2003.terrariatoolbox.domain.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** Immutable blobs plus an atomic pointer: interrupted candidates never become the current copy. */
class CopyWorkspaceStore(private val root: File, private val writer: AtomicWriter,
    private val clock: () -> Long = System::currentTimeMillis) : CopyWorkspaceRepository {
    private val pointer get() = File(root, "current.json")
    private fun blob(id: String): File {
        if (UUID.fromString(id).toString() != id) throw ToolException("副本内容标识无效。")
        return File(root, "$id.bin")
    }
    override fun load(): CopyWorkspace? {
        if (!existsAtomic(pointer)) return null
        return try {
            parseObject(readAtomic(pointer)).let {
                CopyWorkspace(it.string("id"), it.getValue("source").jsonObject.source(), it.string("contentId"),
                    it.long("size"), it.string("hash"), it.long("updatedAt"))
            }
        } catch (_: Exception) { throw ToolException("当前副本记录无法读取，请先导出历史备份并检查内部存储。") }
    }
    override fun read(workspace: CopyWorkspace): ByteArray = try {
        val file = blob(workspace.contentId)
        if (file.length() > MAX_CONFIG_BYTES) throw ToolException("副本超过 8 MiB，禁止操作。")
        readAtomic(file).also {
            if (it.size.toLong() != workspace.size || contentHash(it) != workspace.hash)
                throw ToolException("编辑副本校验失败，请导出原始备份或重新导入。")
        }
    } catch (error: ToolException) { throw error }
    catch (_: Exception) { throw ToolException("无法读取编辑副本，历史备份仍可导出。") }

    override fun commit(id: String, source: SourceInfo, bytes: ByteArray): CopyWorkspace {
        if (bytes.size > MAX_CONFIG_BYTES) throw ToolException("副本超过 8 MiB，禁止保存。")
        try {
            val record = CopyWorkspace(id, source, UUID.randomUUID().toString(), bytes.size.toLong(), contentHash(bytes), clock())
            writer.write(blob(record.contentId), bytes)
            if (!read(record).contentEquals(bytes)) throw ToolException("副本保存校验失败。")
            writer.write(pointer, buildJsonObject {
                put("id", record.id); put("source", record.source.toJson()); put("contentId", record.contentId)
                put("size", record.size); put("hash", record.hash); put("updatedAt", record.updatedAt)
            }.bytes())
            if (load() != record) throw ToolException("副本记录保存校验失败。")
            return record
        } catch (error: ToolException) { throw error }
        catch (_: Exception) { throw ToolException("副本保存失败，请检查空间后重试。") }
    }
}
