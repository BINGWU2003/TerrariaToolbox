package io.github.bingwu2003.terrariatoolbox.domain

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val MAX_SAVE_BYTES = 64L * 1024 * 1024
const val MAX_SAVE_BATCH_BYTES = 256L * 1024 * 1024
const val MAX_SAVE_COUNT = 500
enum class GameEdition(val code: Int, val label: String) { INTERNATIONAL(0, "国际服"), CHINESE(1, "国服") }
enum class SaveKind(val directory: String, val label: String) { PLAYER("Players", "角色"), WORLD("Worlds", "世界") }
data class SaveTarget(val edition: GameEdition, val account: String = "") {
    val label get() = edition.label + if (edition == GameEdition.CHINESE) " · 账号 $account" else ""
}
data class GameSave(val name: String, val size: Long, val modified: Long) {
    val kind get() = if (SaveNames.directory(name) == "Players") SaveKind.PLAYER else SaveKind.WORLD
}
data class StagedSave(val name: String, val file: File, val edition: GameEdition? = null, val origin: SaveOrigin = SaveOrigin.UNKNOWN) {
    val kind get() = if (SaveNames.directory(name) == "Players") SaveKind.PLAYER else SaveKind.WORLD
}
data class SaveImportResult(val original: String, val savedAs: String? = null, val error: String? = null)
interface RemoteSaveFiles {
    fun accounts(): List<String>
    fun list(target: SaveTarget): List<GameSave>
    fun read(target: SaveTarget, name: String, output: OutputStream)
    fun import(target: SaveTarget, save: StagedSave): String
}

fun saveFileHash(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Stages input outside the game directory; limits apply to actual decompressed bytes. */
class SaveStager(private val root: File) {
    private var total = 0L
    private var count = 0
    fun stage(name: String, input: InputStream): List<StagedSave> {
        root.mkdirs()
        return if (name.endsWith(".zip", ignoreCase = true)) unzip(input) else listOf(copy(name, input))
    }
    private fun copy(name: String, input: InputStream): StagedSave {
        try { SaveNames.directory(name) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("文件名不受支持，请选择有效的 .plr 或 .wld 存档。") }
        require(++count <= MAX_SAVE_COUNT) { "一次最多选择 $MAX_SAVE_COUNT 个存档。" }
        val file = File(root, UUID.randomUUID().toString())
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n; total += n
                    require(size <= MAX_SAVE_BYTES && total <= MAX_SAVE_BATCH_BYTES) { "存档过大：单个上限 64 MB，每批上限 256 MB。" }
                    output.write(buffer, 0, n)
                }
                require(size > 0) { "存档文件为空。" }
                output.flush(); output.fd.sync()
            }
            val edition = worldEdition(name, file)
            return StagedSave(name, file, edition, if (edition == null) SaveOrigin.UNKNOWN else SaveOrigin.WORLD_HEADER)
        } catch (error: Exception) { file.delete(); throw error }
    }
    private fun unzip(input: InputStream): List<StagedSave> {
        val saves = mutableListOf<StagedSave>()
        val paths = mutableListOf<String>()
        var manifest: SaveBundle? = null
        try {
            ZipInputStream(input).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_SAVE_COUNT * 4) { "ZIP 中的文件数量过多。" }
                    val parts = entry.name.split('/')
                    require(!entry.name.contains('\\') && !entry.name.startsWith('/') && parts.none { it == ".." || it == "." }) { "ZIP 包含不安全的路径。" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = parts.last()
                    if (entry.name == SAVE_BUNDLE_MANIFEST) {
                        if (manifest != null) throw SaveCompatibilityException("ZIP 包含重复的来源信息，已取消整批导入。")
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total += n
                            if (bytes.size() + n > MAX_MANIFEST_BYTES || total > MAX_SAVE_BATCH_BYTES)
                                throw SaveCompatibilityException("ZIP 来源信息过大，已取消整批导入。")
                            bytes.write(buffer, 0, n)
                        }
                        manifest = SaveBundle.read(bytes.toByteArray())
                    } else if (name.endsWith(".plr", true) || name.endsWith(".wld", true)) {
                        val directory = SaveNames.directory(name)
                        require(parts.size == 1 || (parts.size == 2 && parts.first().equals(directory, true))) { "ZIP 存档应位于 Players 或 Worlds 文件夹。" }
                        saves += copy(name, zip)
                        paths += entry.name
                    }
                    // Limit ignored entries too: closeEntry otherwise decompresses without a bound.
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= MAX_SAVE_BATCH_BYTES) { "ZIP 解压后的内容过大。" }
                    }
                    zip.closeEntry()
                }
            }
            require(saves.isNotEmpty()) { "ZIP 中没有 .plr 或 .wld 存档。" }
            val bundle = manifest
            val result = if (bundle == null) saves.toList() else {
                if (paths.distinct().size != paths.size || paths.toSet() != bundle.saves.map { it.path }.toSet())
                    throw SaveCompatibilityException("ZIP 文件清单与来源信息不一致，已取消整批导入。请重新导出。")
                val records = bundle.saves.associateBy { it.path }
                saves.mapIndexed { index, save ->
                    val record = records.getValue(paths[index])
                    if (save.file.length() != record.size || saveFileHash(save.file) != record.hash)
                        throw SaveCompatibilityException("${save.name} 与 ZIP 文件校验信息不一致，已取消整批导入。")
                    save.withEdition(bundle.edition, SaveOrigin.BUNDLE)
                }
            }
            ensureSingleEdition(result)
            return result
        } catch (error: Exception) { saves.forEach { it.file.delete() }; throw error }
    }
}

fun exportSaves(remote: RemoteSaveFiles, target: SaveTarget, saves: List<GameSave>, file: File) {
    require(saves.isNotEmpty() && saves.size <= MAX_SAVE_COUNT)
    require(saves.sumOf { it.size } <= MAX_SAVE_BATCH_BYTES) { "本次导出超过 256 MB，请分批选择。" }
    var total = 0L
    fun transfer(save: GameSave, output: OutputStream): BundleSave {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        remote.read(target, save.name, object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                size += length; total += length
                require(size <= MAX_SAVE_BYTES && total <= MAX_SAVE_BATCH_BYTES) { "存档内容超过导出上限，请分批处理。" }
                digest.update(bytes, offset, length)
                output.write(bytes, offset, length)
            }
        })
        require(size > 0) { "存档文件为空，已取消导出。" }
        return BundleSave("${save.kind.directory}/${save.name}", size, digest.digest().joinToString("") { "%02x".format(it) })
    }
    file.outputStream().use { output ->
        if (saves.size == 1) transfer(saves.single(), output)
        else ZipOutputStream(output).use { zip ->
            val records = saves.map { save ->
                zip.putNextEntry(ZipEntry("${save.kind.directory}/${save.name}"))
                val record = transfer(save, zip)
                zip.closeEntry()
                record
            }
            zip.putNextEntry(ZipEntry(SAVE_BUNDLE_MANIFEST))
            zip.write(SaveBundle(target.edition, records).bytes())
            zip.closeEntry()
        }
    }
}
