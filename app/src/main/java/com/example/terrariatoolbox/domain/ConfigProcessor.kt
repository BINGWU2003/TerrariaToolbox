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
        Language.ENGLISH -> "英文"
        Language.CHINESE -> "中文"
        Language.UNSUPPORTED -> "暂不支持当前游戏语言"
    }
}

private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
const val MAX_CONFIG_BYTES = 8 * 1024 * 1024

fun contentHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

class ConfigProcessor {
    private val json = Json { prettyPrint = true }

    fun inspect(bytes: ByteArray): ConfigDocument {
        if (bytes.isEmpty()) throw ToolException("游戏设置文件是空的。请先打开一次游戏，再完全退出后重试。")
        if (bytes.size > MAX_CONFIG_BYTES) throw ToolException("游戏设置文件过大，暂时无法处理。")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw ToolException("游戏设置文件无法识别，已取消操作。")
        }
        StrictJsonCheck(text).check()
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw ToolException("游戏设置文件格式不受支持，已取消操作。")
        val field = root["Language"] as? JsonPrimitive
        if (field == null || field.isString || !INTEGER.matches(field.content)) {
            throw ToolException("无法识别游戏语言，请先打开一次游戏，再完全退出后重试。")
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

    fun chineseCandidate(original: ByteArray): ByteArray = languageCandidate(original, Language.CHINESE)

    fun languageCandidate(original: ByteArray, target: Language): ByteArray {
        require(target != Language.UNSUPPORTED)
        val document = requireSupported(original)
        if (document.language == target) return original.copyOf()
        val candidate = JsonObject(document.root.toMutableMap().apply {
            put("Language", JsonPrimitive(if (target == Language.CHINESE) 7 else 1))
        })
            .toString().toByteArray(Charsets.UTF_8)
        verifyLanguage(original, candidate, target)
        return candidate
    }

    fun verifyChinese(original: ByteArray, candidate: ByteArray) = verifyLanguage(original, candidate, Language.CHINESE)

    fun verifyLanguage(original: ByteArray, candidate: ByteArray, target: Language) {
        require(target != Language.UNSUPPORTED)
        val before = requireSupported(original)
        val after = requireSupported(candidate)
        if (after.language != target || before.root.filterKeys { it != "Language" } !=
            after.root.filterKeys { it != "Language" }) {
            throw ToolException("检查未通过：其他游戏设置发生了变化，已取消操作。")
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
        if (depth > 128) throw ToolException("游戏设置文件内容过于复杂，暂时无法处理。")
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
            if (!keys.add(key)) throw ToolException("游戏设置文件中有重复的设置项，已取消操作。")
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
    private fun invalid(): Nothing = throw ToolException("游戏设置文件格式有误，已取消操作。")
}
