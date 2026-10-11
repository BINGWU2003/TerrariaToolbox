package io.github.bingwu2003.terrariatoolbox.domain

import kotlinx.serialization.json.*
import java.io.File

enum class SaveOrigin(val label: String) {
    UNKNOWN("待声明"), WORLD_HEADER("世界标记"), BUNDLE("ZIP 来源信息"), BUILTIN("内置资源"), USER("用户声明")
}

class SaveCompatibilityException(message: String) : IllegalArgumentException(message)

/** Identifies known world signatures only; this is not a complete Terraria format validator. */
fun worldEdition(name: String, file: File): GameEdition? {
    if (!name.endsWith(".wld", true)) return null
    val header = file.inputStream().use { it.readNBytes(11) }
    if (header.size != 11) return null
    return when (String(header, 4, 7, Charsets.US_ASCII)) {
        "relogic" -> GameEdition.INTERNATIONAL
        "xindong" -> GameEdition.CHINESE
        else -> null
    }
}

fun StagedSave.withEdition(edition: GameEdition, origin: SaveOrigin): StagedSave {
    if (this.edition != null && this.edition != edition)
        throw SaveCompatibilityException("${name} 的所属服信息冲突，已取消整批导入。请检查来源后重新选择。")
    return copy(edition = edition, origin = origin)
}

fun ensureSingleEdition(saves: List<StagedSave>) {
    if (saves.mapNotNull { it.edition }.distinct().size > 1)
        throw SaveCompatibilityException("所选文件混有国际服和国服存档，已取消整批导入。请按所属服分批选择。")
}

fun resolvedEdition(saves: List<StagedSave>, declaration: GameEdition?): GameEdition? {
    val known = saves.mapNotNull { it.edition }.distinct()
    if (saves.isEmpty() || known.size > 1) return null
    if (saves.none { it.edition == null }) return known.singleOrNull()
    return declaration?.takeIf { known.isEmpty() || known.single() == it }
}

fun checkSaveEdition(target: SaveTarget, save: StagedSave) {
    if (save.edition != target.edition || worldEdition(save.name, save.file)?.let { it != target.edition } == true)
        throw SaveCompatibilityException("${save.name} 与目标所属服不一致，已阻止跨服导入。")
}

const val SAVE_BUNDLE_MANIFEST = "terraria-toolbox.json"
const val MAX_MANIFEST_BYTES = 256 * 1024

data class BundleSave(val path: String, val size: Long, val hash: String)
data class SaveBundle(val edition: GameEdition, val saves: List<BundleSave>) {
    fun bytes(): ByteArray = buildJsonObject {
        put("format", "terraria-toolbox-save-bundle")
        put("schema", 1)
        put("edition", edition.name)
        putJsonArray("saves") { saves.forEach { save -> add(buildJsonObject {
            put("path", save.path); put("size", save.size); put("sha256", save.hash)
        }) } }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun read(bytes: ByteArray): SaveBundle = try {
            require(bytes.size <= MAX_MANIFEST_BYTES)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(value["format"]?.jsonPrimitive?.content == "terraria-toolbox-save-bundle")
            require(value["schema"]?.jsonPrimitive?.int == 1)
            val edition = GameEdition.valueOf(value.getValue("edition").jsonPrimitive.content)
            val saves = value.getValue("saves").jsonArray.map { element ->
                val item = element.jsonObject
                val path = item.getValue("path").jsonPrimitive.content
                val parts = path.split('/')
                require(parts.size == 2 && parts.first() == SaveNames.directory(parts.last()))
                val size = item.getValue("size").jsonPrimitive.long
                val hash = item.getValue("sha256").jsonPrimitive.content
                require(size in 1..MAX_SAVE_BYTES && hash.matches(Regex("[0-9a-f]{64}")))
                BundleSave(path, size, hash)
            }
            require(saves.isNotEmpty() && saves.size <= MAX_SAVE_COUNT && saves.map { it.path }.distinct().size == saves.size)
            SaveBundle(edition, saves)
        } catch (_: Exception) {
            throw SaveCompatibilityException("ZIP 来源信息无效或版本不受支持，已取消整批导入。请重新导出或选择原存档文件。")
        }
    }
}
