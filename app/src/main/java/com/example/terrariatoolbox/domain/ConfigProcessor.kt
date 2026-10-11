package com.example.terrariatoolbox.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

open class ToolException(val userMessage: String, val needsRecovery: Boolean = false) : Exception(userMessage)
/** Only used when the backend can prove truncation has not started. */
class WriteNotStartedException(message: String) : ToolException(message)

enum class Language { ENGLISH, CHINESE, UNSUPPORTED }

data class ConfigDocument(val root: JsonObject, val language: Language, val languageNumber: String) {
    val version: String? get() = (root["LastLaunchedVersion"] as? JsonPrimitive)
        ?.takeIf { !it.isString && INTEGER.matches(it.content) }?.content
    val languageLabel: String get() = when (language) {
        Language.ENGLISH -> "英文（1）"
        Language.CHINESE -> "配置已设为中文（7）"
        Language.UNSUPPORTED -> "暂不支持的语言编号：${languageNumber.take(48)}"
    }
}

private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
const val MAX_CONFIG_BYTES = 8 * 1024 * 1024

fun contentHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

class ConfigProcessor {
    private val json = Json { prettyPrint = true }

    fun inspect(bytes: ByteArray): ConfigDocument {
        if (bytes.isEmpty()) throw ToolException("配置文件为空，请检查选择的位置。")
        if (bytes.size > MAX_CONFIG_BYTES) throw ToolException("文件超过 8 MiB，无法作为配置处理。")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw ToolException("配置不是有效的 UTF-8 JSON 文件。")
        }
        StrictJsonCheck(text).check()
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw ToolException("配置不受支持：JSON 根节点必须是对象。")
        val field = root["Language"] as? JsonPrimitive
        if (field == null || field.isString || !INTEGER.matches(field.content)) {
            throw ToolException("配置不受支持：根对象的 Language 必须是整数，不能是字符串或小数。")
        }
        val language = when (field.content) {
            "1" -> Language.ENGLISH
            "7" -> Language.CHINESE
            else -> Language.UNSUPPORTED
        }
        return ConfigDocument(root, language, field.content)
    }

    fun requireSupported(bytes: ByteArray): ConfigDocument = inspect(bytes).also {
        if (it.language == Language.UNSUPPORTED) throw ToolException(it.languageLabel + "，未修改文件。")
    }

    fun chineseCandidate(original: ByteArray): ByteArray {
        val document = requireSupported(original)
        if (document.language == Language.CHINESE) return original.copyOf()
        val candidate = JsonObject(document.root.toMutableMap().apply { put("Language", JsonPrimitive(7)) })
            .toString().toByteArray(Charsets.UTF_8)
        verifyChinese(original, candidate)
        return candidate
    }

    fun verifyChinese(original: ByteArray, candidate: ByteArray) {
        val before = requireSupported(original)
        val after = requireSupported(candidate)
        if (after.language != Language.CHINESE || before.root.filterKeys { it != "Language" } !=
            after.root.filterKeys { it != "Language" }) {
            throw ToolException("配置校验失败：语言以外的内容发生变化。")
        }
    }
}

/** Checks strict JSON grammar and decoded keys before a tree parser can discard duplicates. */
private class StrictJsonCheck(private val text: String) {
    private var position = 0

    fun check() {
        value(0)
        whitespace()
        if (position != text.length) invalid()
    }

    private fun value(depth: Int) {
        if (depth > 128) throw ToolException("JSON 嵌套过深，配置不受支持。")
        whitespace()
        when (peek()) {
            '{' -> obj(depth + 1)
            '[' -> array(depth + 1)
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> invalid()
        }
    }

    private fun obj(depth: Int) {
        position++
        whitespace()
        if (accept('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            whitespace()
            if (peek() != '"') invalid()
            val token = string()
            val key = try { (Json.parseToJsonElement(token) as JsonPrimitive).content }
                catch (_: Exception) { invalid() }
            if (!keys.add(key)) throw ToolException("配置不受支持：JSON 对象存在重复键。")
            whitespace()
            expect(':')
            value(depth)
            whitespace()
            if (accept('}')) return
            expect(',')
        }
    }

    private fun array(depth: Int) {
        position++
        whitespace()
        if (accept(']')) return
        while (true) {
            value(depth)
            whitespace()
            if (accept(']')) return
            expect(',')
        }
    }

    private fun string(): String {
        val start = position
        expect('"')
        while (position < text.length) {
            val char = text[position++]
            if (char == '"') return text.substring(start, position)
            if (char < ' ') invalid()
            if (char == '\\') {
                if (position >= text.length) invalid()
                when (text[position++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        if (position >= text.length || text[position++].digitToIntOrNull(16) == null) invalid()
                    }
                    else -> invalid()
                }
            }
        }
        invalid()
    }

    private fun number() {
        accept('-')
        if (!accept('0')) {
            if (peek() !in '1'..'9') invalid()
            while (peek() in '0'..'9') position++
        }
        if (accept('.')) digits()
        if (accept('e') || accept('E')) {
            if (!accept('+')) accept('-')
            digits()
        }
    }

    private fun digits() {
        if (peek() !in '0'..'9') invalid()
        while (peek() in '0'..'9') position++
    }
    private fun literal(value: String) {
        if (!text.startsWith(value, position)) invalid()
        position += value.length
    }
    private fun whitespace() { while (peek() in listOf(' ', '\t', '\r', '\n')) position++ }
    private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
    private fun accept(char: Char): Boolean = if (peek() == char) { position++; true } else false
    private fun expect(char: Char) { if (!accept(char)) invalid() }
    private fun invalid(): Nothing = throw ToolException("JSON 格式错误，配置不受支持，未修改文件。")
}
