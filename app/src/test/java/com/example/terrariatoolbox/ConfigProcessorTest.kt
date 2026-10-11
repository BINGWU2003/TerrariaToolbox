package com.example.terrariatoolbox

import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Test

class ConfigProcessorTest {
    private val processor = ConfigProcessor()
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test fun englishSwitchPreservesRealGameSettingsAndRejectsWrongTargetOrOtherChanges() {
        val original = javaClass.classLoader!!.getResourceAsStream("config.json")!!.use { it.readBytes() }
        val output = processor.languageCandidate(original, Language.ENGLISH)
        processor.verifyLanguage(original, output, Language.ENGLISH)
        assertEquals("1", processor.inspect(output).languageNumber)
        assertArrayEquals(output, processor.languageCandidate(output, Language.ENGLISH))
        val chinese = processor.languageCandidate(output, Language.CHINESE)
        assertEquals(processor.inspect(original).root, processor.inspect(chinese).root)
        assertThrows(ToolException::class.java) { processor.verifyLanguage(original, output, Language.CHINESE) }
        assertThrows(ToolException::class.java) {
            processor.verifyLanguage(bytes("{\"Language\":7,\"volume\":0.5}"), bytes("{\"Language\":1,\"volume\":1}"), Language.ENGLISH)
        }
    }

    @Test fun realSampleAndEnglishCopyPreserveEveryOtherField() {
        val sample = javaClass.classLoader!!.getResourceAsStream("config.json")!!.use { it.readBytes() }
        assertEquals(Language.CHINESE, processor.inspect(sample).language)
        assertArrayEquals(sample, processor.chineseCandidate(sample))
        // Only the fixture copy is changed; docs/config.json is read as a test resource.
        val english = bytes(sample.toString(Charsets.UTF_8).replace("\"Language\": 7", "\"Language\": 1"))
        assertEquals(Language.ENGLISH, processor.inspect(english).language)
        val output = processor.chineseCandidate(english)
        processor.verifyChinese(english, output)
        assertEquals("7", processor.inspect(output).languageNumber)
        assertEquals("326", processor.inspect(output).version)
        assertEquals(Language.CHINESE, processor.inspect(sample).language)
    }

    @Test fun preservesUnknownNestedValuesAndNumericLexemes() {
        val input = bytes("""{"Language":1,"large":123456789012345678901234567890,"decimal":0.12345678901234567890123456789,"exponent":1.234567890123456789e+100,"unknown":[{},null,true,"中文",{"Language":2}],"empty":{}}""")
        val output = processor.chineseCandidate(input)
        processor.verifyChinese(input, output)
        assertTrue(output.toString(Charsets.UTF_8).contains("0.12345678901234567890123456789"))
        assertTrue(output.toString(Charsets.UTF_8).contains("1.234567890123456789e+100"))
    }

    @Test fun rejectsInvalidStructureTypesAndStrictJsonErrors() {
        listOf("", "[]", "{}", "{\"language\":1}", "{\"nested\":{\"Language\":1}}",
            "{\"Language\":\"7\"}", "{\"Language\":7.0}", "{\"Language\":7e0}", "{\"Language\":true}",
            "{\"Language\":null}", "{Language:1}", "{\"Language\":01}", "{\"Language\":1,}",
            "{\"Language\":1} trailing", "{\"Language\":1,\"x\":NaN}", "{\"Language\":1,\"x\":\"\n\"}")
            .forEach { input -> assertThrows(ToolException::class.java) { processor.inspect(bytes(input)) } }
    }

    @Test fun rejectsDuplicatesIncludingNestedAndEscapedEquivalentKeys() {
        listOf("""{"Language":1,"Language":7}""", """{"Language":1,"x":{"a":0,"a":1}}""",
            """{"Language":1,"arr":[{"a":0,"\u0061":1}]}""", """{"Language":1,"\u004canguage":7}""")
            .forEach { assertThrows(ToolException::class.java) { processor.inspect(bytes(it)) } }
    }

    @Test fun unknownIntegersAreIdentifiedButNeverModified() {
        listOf("2", "0", "-1", "999999999999999999999999999999999999999").forEach {
            val input = bytes("{\"Language\":$it}")
            assertEquals(Language.UNSUPPORTED, processor.inspect(input).language)
            assertThrows(ToolException::class.java) { processor.chineseCandidate(input) }
        }
    }

    @Test fun rejectsMalformedUtf8DeepNestingAndOversizeInput() {
        assertThrows(ToolException::class.java) { processor.inspect(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(ToolException::class.java) { processor.inspect(bytes("[".repeat(130) + "0" + "]".repeat(130))) }
        assertThrows(ToolException::class.java) { processor.inspect(ByteArray(MAX_CONFIG_BYTES + 1)) }
    }

    @Test fun allowsBomAndEscapedPunctuationAndRejectsNonLanguageChanges() {
        val input = bytes("\uFEFF{\"Language\":1,\"text\":\"a\\\"b\\\\c{}:,\\u4e2d\"}")
        processor.verifyChinese(input, processor.chineseCandidate(input))
        assertThrows(ToolException::class.java) {
            processor.verifyChinese(bytes("{\"Language\":1,\"x\":true}"), bytes("{\"Language\":7,\"x\":false}"))
        }
    }
}
