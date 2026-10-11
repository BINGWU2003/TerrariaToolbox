package com.example.terrariatoolbox

import com.example.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun world(marker: String) = byteArrayOf(0x46, 1, 0, 0) + marker.toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3)
    private fun stage(name: String, bytes: ByteArray) = SaveStager(temp.newFolder()).stage(name, ByteArrayInputStream(bytes))
    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, data) -> zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    private fun manifest(edition: GameEdition, path: String, bytes: ByteArray): ByteArray {
        val file = temp.newFile().apply { writeBytes(bytes) }
        return SaveBundle(edition, listOf(BundleSave(path, bytes.size.toLong(), saveFileHash(file)))).bytes()
    }
    @Test fun sameWorldVersionWithDifferentSignaturesResolvesDifferentEditions() {
        assertEquals(GameEdition.INTERNATIONAL, stage("a.wld", world("relogic")).single().edition)
        assertEquals(GameEdition.CHINESE, stage("b.wld", world("xindong")).single().edition)
    }
    @Test fun encryptedPlayersAndUnknownOrShortWorldsRequireExplicitDeclaration() {
        val saves = stage("relogic.plr", world("relogic"))
        assertNull(saves.single().edition)
        assertNull(resolvedEdition(saves, null))
        assertEquals(GameEdition.CHINESE, resolvedEdition(saves, GameEdition.CHINESE))
        assertNull(stage("relogic.wld", byteArrayOf(1, 2)).single().edition)
        assertNull(stage("xindong.wld", world("unknown")).single().edition)
    }
    @Test fun declaredEditionCannotOverrideIdentifiedWorldInMixedBatch() {
        val saves = stage("known.wld", world("xindong")) + stage("unknown.plr", byteArrayOf(1))
        assertNull(resolvedEdition(saves, null))
        assertNull(resolvedEdition(saves, GameEdition.INTERNATIONAL))
        assertEquals(GameEdition.CHINESE, resolvedEdition(saves, GameEdition.CHINESE))
    }
    @Test fun oppositeWorldsInLegacyZipRejectWholeZipAndRemoveTemporaryFiles() {
        val root = temp.newFolder()
        assertThrows(SaveCompatibilityException::class.java) {
            SaveStager(root).stage("mixed.zip", ByteArrayInputStream(zip("Worlds/a.wld" to world("relogic"), "Worlds/b.wld" to world("xindong"))))
        }
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun bundleMetadataRecognizesPlayerRegardlessOfZipFilenameAndManifestOrder() {
        val bytes = byteArrayOf(8, 7, 6)
        val meta = manifest(GameEdition.CHINESE, "Players/a.plr", bytes)
        for (entries in listOf(arrayOf(SAVE_BUNDLE_MANIFEST to meta, "Players/a.plr" to bytes),
            arrayOf("Players/a.plr" to bytes, SAVE_BUNDLE_MANIFEST to meta))) {
            val save = stage("renamed-international.zip", zip(*entries)).single()
            assertEquals(GameEdition.CHINESE, save.edition)
            assertEquals(SaveOrigin.BUNDLE, save.origin)
            assertArrayEquals(bytes, save.file.readBytes())
        }
    }
    @Test fun wrongBundleHashOrMissingOrExtraFileRejectsWholeBundle() {
        val bytes = byteArrayOf(1, 2, 3)
        val meta = manifest(GameEdition.INTERNATIONAL, "Players/a.plr", bytes)
        val invalid = listOf(
            zip(SAVE_BUNDLE_MANIFEST to meta, "Players/a.plr" to byteArrayOf(4, 5, 6)),
            zip(SAVE_BUNDLE_MANIFEST to meta, "Players/b.plr" to bytes),
            zip(SAVE_BUNDLE_MANIFEST to meta, "Players/a.plr" to bytes, "Players/b.plr" to bytes))
        invalid.forEach { input ->
            val root = temp.newFolder()
            assertThrows(SaveCompatibilityException::class.java) { SaveStager(root).stage("bad.zip", ByteArrayInputStream(input)) }
            assertEquals(0, root.listFiles()!!.size)
        }
    }
    @Test fun bundleEditionCannotOverrideKnownWorldHeaderEvenWithCorrectHash() {
        val bytes = world("relogic")
        assertThrows(SaveCompatibilityException::class.java) {
            stage("bad.zip", zip("Worlds/a.wld" to bytes, SAVE_BUNDLE_MANIFEST to manifest(GameEdition.CHINESE, "Worlds/a.wld", bytes)))
        }
    }
    @Test fun malformedUnknownSchemaAndOversizedManifestAreRejected() {
        val valid = manifest(GameEdition.INTERNATIONAL, "Players/a.plr", byteArrayOf(1))
        listOf("{}".toByteArray(), valid.toString(Charsets.UTF_8).replace("\"schema\":1", "\"schema\":2").toByteArray(),
            ByteArray(MAX_MANIFEST_BYTES + 1) { 32 }).forEach { meta ->
            assertThrows(SaveCompatibilityException::class.java) { stage("bad.zip", zip(SAVE_BUNDLE_MANIFEST to meta, "Players/a.plr" to byteArrayOf(1))) }
        }
    }
    @Test fun legacyZipWithoutSourceMetadataStillNeedsDeclarationForPlayers() {
        assertNull(resolvedEdition(stage("old-chinese.zip", zip("Players/a.plr" to byteArrayOf(1))), null))
    }
    @Test fun builtinOrUserDeclarationCannotOverrideHeaderAndWriteBoundaryRechecksIt() {
        val save = stage("a.wld", world("relogic")).single()
        assertThrows(SaveCompatibilityException::class.java) { save.withEdition(GameEdition.CHINESE, SaveOrigin.BUILTIN) }
        assertThrows(SaveCompatibilityException::class.java) { checkSaveEdition(SaveTarget(GameEdition.CHINESE, "53399"), save) }
        assertThrows(SaveCompatibilityException::class.java) { checkSaveEdition(SaveTarget(GameEdition.CHINESE, "53399"), save.copy(edition = GameEdition.CHINESE)) }
        checkSaveEdition(SaveTarget(GameEdition.INTERNATIONAL), save)
    }
}
