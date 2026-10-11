package com.example.terrariatoolbox

import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveManagementTest {
    @get:Rule val temp = TemporaryFolder()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    @Test fun duplicateNamesIgnoreCaseAndKeepExtensionAndExistingSuffix() {
        assertEquals("Vor (2).plr", SaveNames.available("Vor.plr", listOf("vor.PLR", "VOR (1).plr")))
        assertEquals("Map(3) (1).WLD", SaveNames.available("Map(3).WLD", listOf("map(3).wld")))
        assertEquals("Vor.plr", SaveNames.available("Vor.plr", listOf("Vor.wld")))
        assertEquals("Players", SaveNames.directory("A + 测试 (1).plr"))
    }
    @Test fun unsafeAndUnsupportedFilenamesAreRejected() {
        listOf("../Vor.plr", "x\\Vor.plr", "/Vor.plr", ".plr", "test.plr.bak", "\u0000.plr", "x".repeat(240) + ".wld").forEach {
            assertThrows(IllegalArgumentException::class.java) { SaveNames.directory(it) }
        }
    }
    @Test fun longUnicodeNamesRemainReadableAfterAutomaticRename() {
        val name = "存".repeat(78) + ".plr"
        val renamed = SaveNames.available(name, listOf(name))
        assertEquals("Players", SaveNames.directory(renamed))
        assertTrue(renamed.endsWith(" (1).plr"))
        assertTrue(renamed.toByteArray().size <= 240)
    }
    @Test fun exportedMixedZipCanBeImportedWithExactNamesAndBytes() {
        val bytes = mapOf("Vor.plr" to byteArrayOf(0, 1, 2, -1), "All + Items (3).wld" to byteArrayOf(7, 8, 0))
        val remote = object : RemoteSaveFiles {
            override fun accounts() = emptyList<String>()
            override fun list(target: SaveTarget) = bytes.map { GameSave(it.key, it.value.size.toLong(), 0) }
            override fun read(target: SaveTarget, name: String, output: OutputStream) { output.write(bytes.getValue(name)) }
            override fun import(target: SaveTarget, save: StagedSave): String = error("unused")
        }
        val target = SaveTarget(GameEdition.INTERNATIONAL)
        val output = temp.newFile()
        exportSaves(remote, target, remote.list(target), output)
        val staged = SaveStager(temp.newFolder()).stage("backup.zip", output.inputStream())
        assertEquals(bytes.keys, staged.map { it.name }.toSet())
        assertTrue(staged.all { it.edition == GameEdition.INTERNATIONAL && it.origin == SaveOrigin.BUNDLE })
        staged.forEach { assertArrayEquals(bytes.getValue(it.name), it.file.readBytes()) }
        val single = temp.newFile()
        exportSaves(remote, target, listOf(remote.list(target).first()), single)
        assertArrayEquals(bytes.values.first(), single.readBytes())
    }
    @Test fun invalidZipIsRejectedBeforeAnyGameWriteAndDeletesStagedFiles() {
        val root = temp.newFolder()
        val input = zip("Players/ok.plr" to byteArrayOf(1), "../Worlds/evil.wld" to byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) { SaveStager(root).stage("bad.zip", ByteArrayInputStream(input)) }
        assertEquals(0, root.listFiles()!!.size)
        val wrongKind = zip("Worlds/character.plr" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { SaveStager(root).stage("bad.zip", ByteArrayInputStream(wrongKind)) }
    }
    @Test fun zipKeepsDuplicateCandidatesForAutomaticRenameAndExcludesSidecars() {
        val input = zip("Players/Vor.plr" to byteArrayOf(1), "vor.PLR" to byteArrayOf(2), "Players/Vor.plr.bak" to byteArrayOf(3), "Players/map.dat" to byteArrayOf(4))
        val staged = SaveStager(temp.newFolder()).stage("input.ZIP", ByteArrayInputStream(input))
        assertEquals(listOf("Vor.plr", "vor.PLR"), staged.map { it.name })
        assertArrayEquals(byteArrayOf(2), staged.last().file.readBytes())
    }
    @Test fun emptyAndTruncatedInputsCannotBecomeSuccessfulImports() {
        assertThrows(IllegalArgumentException::class.java) { SaveStager(temp.newFolder()).stage("x.plr", ByteArrayInputStream(byteArrayOf())) }
        val data = zip("Players/a.plr" to ByteArray(100000) { (it % 251).toByte() })
        assertThrows(Exception::class.java) { SaveStager(temp.newFolder()).stage("broken.zip", ByteArrayInputStream(data.copyOf(data.size / 2))) }
    }
}
