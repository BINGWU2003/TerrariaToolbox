package io.github.bingwu2003.terrariatoolbox

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.domain.*
import io.github.bingwu2003.terrariatoolbox.data.SavePreferences
import io.github.bingwu2003.terrariatoolbox.feature.saves.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.io.File
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SaveManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val storage = mutableMapOf<SaveTarget, MutableMap<String, ByteArray>>()
    private var accountIds = listOf("53399")
    private var writes = 0
    private var remoteConnected = true
    private var remoteCalls = 0
    private val preferencesFile = File(app.cacheDir, "test-save-target-${UUID.randomUUID()}.json")
    private val remote = object : RemoteSaveFiles {
        override fun accounts() = accountIds
        override fun list(target: SaveTarget) = storage[target].orEmpty().map { GameSave(it.key, it.value.size.toLong(), 1000) }
        override fun read(target: SaveTarget, name: String, output: OutputStream) { output.write(storage.getValue(target).getValue(name)) }
        override fun import(target: SaveTarget, save: StagedSave): String {
            val saves = storage.getOrPut(target) { mutableMapOf() }
            val name = SaveNames.available(save.name, saves.keys)
            saves[name] = save.file.readBytes(); writes++; return name
        }
    }
    private lateinit var manager: SaveManager
    private fun start() { main { manager = SaveManager(app, {
        remoteCalls++
        check(remoteConnected) { "Disconnected" }
        remote
    }, scope, SavePreferences(preferencesFile)) }; waitFor { manager.state.value.builtins.isNotEmpty() && !manager.state.value.busy } }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(condition: () -> Boolean = { !manager.state.value.busy }) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(20) }
        fail("Timed out: ${manager.state.value}")
    }
    @After fun cleanup() { scope.cancel(); preferencesFile.delete() }
    @Test fun accountDiscoveryAutoSelectsSingleAndRequiresChoiceForMultiple() {
        start(); main { manager.edition(GameEdition.CHINESE) }; waitFor()
        assertEquals("53399", manager.state.value.account)
        accountIds = listOf("53399", "98765")
        main { manager.edition(GameEdition.CHINESE) }; waitFor()
        assertEquals("", manager.state.value.account)
        assertFalse(manager.state.value.hasTarget)
        main { manager.account("98765") }; waitFor()
        assertEquals("98765", manager.state.value.target.account)
    }
    @Test fun builtinsAreOnlyImportedAfterConfirmationAndReportRenamedFileWithExactBytes() {
        start(); main { manager.refresh() }; waitFor()
        val target = SaveTarget(GameEdition.INTERNATIONAL)
        val existing = byteArrayOf(3, 2, 1)
        storage[target] = mutableMapOf("vor.PLR" to existing)
        val assets = manager.state.value.builtins.filter { it.edition == GameEdition.INTERNATIONAL }
        main { manager.prepareBuiltins(assets.map { it.asset }.toSet()) }; waitFor()
        assertEquals(0, writes)
        assertEquals(assets.size, manager.state.value.pending.size)
        assertEquals(GameEdition.INTERNATIONAL, manager.state.value.sourceEdition)
        assertNull(manager.state.value.pendingTarget)
        main { manager.reviewImport() }
        assertEquals(target, manager.state.value.pendingTarget)
        main { manager.confirmImport() }; waitFor()
        assertEquals(assets.size, writes)
        assertArrayEquals(existing, storage.getValue(target).getValue("vor.PLR"))
        assertEquals("Vor (1).plr", manager.state.value.results.single { it.original == "Vor.plr" }.savedAs)
        assets.forEach { asset ->
            val name = manager.state.value.results.single { it.original == asset.name }.savedAs!!
            assertArrayEquals(app.assets.open(asset.asset).use { it.readBytes() }, storage.getValue(target).getValue(name))
        }
        assertTrue(manager.state.value.pending.isEmpty())
        val summary = manager.state.value.importSummary
        main { manager.refresh() }; waitFor()
        assertEquals(summary, manager.state.value.importSummary)
    }
    private fun reset(name: String, bytes: ByteArray, denyReadback: Boolean = false) {
        app.contentResolver.call(Uri.parse("content://$TEST_CONTROL_AUTHORITY"), "test.reset", null, Bundle().apply {
            putString("name", name); putByteArray("bytes", bytes); putBoolean("denyExportRead", denyReadback)
        })
    }
    @Test fun phoneFileImportAndSingleExportAreByteExactAndExportReadbackFailureIsReported() {
        start(); main { manager.refresh() }; waitFor()
        val bytes = byteArrayOf(0, 1, 2, -1, 50)
        reset("Phone.plr", bytes)
        val input = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
        val output = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "export")
        main { manager.preparePhone(listOf(input)) }; waitFor()
        assertEquals(0, writes)
        main { manager.reviewImport(); manager.confirmImport() }
        assertEquals(0, writes)
        main { manager.declareSource(GameEdition.INTERNATIONAL) }
        main { manager.reviewImport() }
        main { manager.confirmImport() }; waitFor()
        main { manager.toggle("Phone.plr") }
        var spec: SaveExportSpec? = null
        main { manager.prepareExport { spec = it } }; waitFor()
        assertEquals("Phone.plr", spec!!.name)
        main { manager.exported(output) }; waitFor()
        assertArrayEquals(bytes, app.contentResolver.openInputStream(output)!!.use { it.readBytes() })
        assertTrue(manager.state.value.message.startsWith("已导出并检查文件"))
        reset("Phone.plr", bytes, denyReadback = true)
        main { manager.prepareExport { spec = it } }; waitFor()
        main { manager.exported(output) }; waitFor()
        assertFalse(manager.state.value.message.startsWith("已导出并检查文件"))
    }

    @Test fun stagingDoesNotNeedConnectionAndCrossEditionTargetChangeIsBlocked() {
        start()
        remoteConnected = false
        val assets = manager.state.value.builtins.take(1).map { it.asset }.toSet()
        main { manager.prepareBuiltins(assets) }; waitFor()
        assertEquals(0, remoteCalls)
        assertEquals(0, writes)
        assertEquals(ImportStep.TARGET, manager.state.value.importStep)
        main { manager.confirmImport() }
        assertEquals(0, writes)
        remoteConnected = true
        main { manager.edition(GameEdition.CHINESE) }; waitFor()
        assertEquals(1, manager.state.value.pending.size)
        assertEquals(GameEdition.INTERNATIONAL, manager.state.value.edition)
        assertTrue(manager.state.value.message.contains("不能导入国服"))
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport() }
        assertEquals(SaveTarget(GameEdition.INTERNATIONAL), manager.state.value.pendingTarget)
        main { manager.edition(GameEdition.CHINESE) }
        assertEquals(SaveTarget(GameEdition.INTERNATIONAL), manager.state.value.pendingTarget)
        main { manager.confirmImport() }; waitFor()
        assertEquals(1, writes)
    }

    @Test fun chineseBuiltinWorldIsIncludedBoundToChineseAndByteExact() {
        start()
        assertEquals(4, manager.state.value.builtins.count { it.edition == GameEdition.INTERNATIONAL && it.kind == SaveKind.PLAYER })
        assertEquals(2, manager.state.value.builtins.count { it.edition == GameEdition.INTERNATIONAL && it.kind == SaveKind.WORLD })
        assertEquals(0, manager.state.value.builtins.count { it.edition == GameEdition.CHINESE && it.kind == SaveKind.PLAYER })
        val save = manager.state.value.builtins.single { it.edition == GameEdition.CHINESE }
        main { manager.prepareBuiltins(setOf(save.asset)) }; waitFor()
        assertEquals(GameEdition.CHINESE, manager.state.value.sourceEdition)
        assertEquals(GameEdition.CHINESE, manager.state.value.edition)
        assertEquals(0, writes)
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport(); manager.confirmImport() }; waitFor()
        assertEquals(1, writes)
        assertArrayEquals(app.assets.open(save.asset).use { it.readBytes() }, storage.getValue(SaveTarget(GameEdition.CHINESE, "53399")).getValue(save.name))
    }

    @Test fun directMixedBuiltinRequestRejectsEntireBatchBeforeWriting() {
        start()
        val assets = listOf(manager.state.value.builtins.first { it.edition == GameEdition.INTERNATIONAL },
            manager.state.value.builtins.first { it.edition == GameEdition.CHINESE })
        main { manager.prepareBuiltins(assets.map { it.asset }.toSet()) }; waitFor()
        assertEquals(ImportStep.RESULT, manager.state.value.importStep)
        assertTrue(manager.state.value.pending.isEmpty())
        assertTrue(manager.state.value.importSummary.contains("混有国际服和国服"))
        main { manager.reviewImport(); manager.confirmImport() }
        assertEquals(0, writes)
        assertEquals(0, remoteCalls)
    }

    @Test fun phoneWorldSignatureBindsEditionAndUnknownCharactersCannotBeAssumed() {
        start()
        val bytes = byteArrayOf(0x46, 1, 0, 0) + "xindong".toByteArray() + byteArrayOf(1)
        reset("National.wld", bytes)
        val input = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
        main { manager.preparePhone(listOf(input)) }; waitFor()
        assertEquals(GameEdition.CHINESE, manager.state.value.sourceEdition)
        main { manager.declareSource(GameEdition.INTERNATIONAL) }
        assertEquals(GameEdition.CHINESE, manager.state.value.sourceEdition)
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport(); manager.confirmImport() }; waitFor()
        assertEquals(1, writes)
        reset("Unknown.plr", byteArrayOf(1, 2, 3))
        main { manager.preparePhone(listOf(input)) }; waitFor()
        assertNull(manager.state.value.sourceEdition)
        main { manager.reviewImport(); manager.confirmImport() }
        assertEquals(1, writes)
    }

    @Test fun unknownBatchDeclarationCannotContradictKnownWorldAndRevalidatesNationalAccount() {
        start()
        val zipBytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("Players/Unknown.plr")); zip.write(byteArrayOf(1, 2)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Worlds/National.wld"))
            zip.write(byteArrayOf(0x46, 1, 0, 0) + "xindong".toByteArray() + byteArrayOf(1)); zip.closeEntry()
        } }.toByteArray()
        reset("old.zip", zipBytes)
        val input = DocumentsContract.buildDocumentUri(TEST_AUTHORITY, "config")
        main { manager.preparePhone(listOf(input)) }; waitFor()
        assertNull(manager.state.value.sourceEdition)
        main { manager.declareSource(GameEdition.INTERNATIONAL); manager.reviewImport(); manager.confirmImport() }
        assertEquals(0, writes)
        assertNull(manager.state.value.declaredEdition)
        main { manager.declareSource(GameEdition.CHINESE) }
        assertEquals(GameEdition.CHINESE, manager.state.value.edition)
        assertFalse(manager.state.value.loaded)
        main { manager.reviewImport(); manager.confirmImport() }
        assertEquals(0, writes)
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport(); manager.confirmImport() }; waitFor()
        assertEquals(2, writes)
    }

    @Test fun changedWorldSignatureAbortsEntireBatchAtFinalPreflightWithZeroWrites() {
        start()
        val save = manager.state.value.builtins.single { it.edition == GameEdition.CHINESE }
        main { manager.prepareBuiltins(setOf(save.asset)) }; waitFor()
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport() }
        manager.state.value.pending.single().file.writeBytes(byteArrayOf(0x46, 1, 0, 0) + "relogic".toByteArray() + byteArrayOf(1))
        main { manager.confirmImport() }; waitFor()
        assertEquals(0, writes)
        assertEquals(ImportStep.RESULT, manager.state.value.importStep)
        assertTrue(manager.state.value.pending.isEmpty())
        assertTrue(manager.state.value.importSummary.contains("已阻止跨服导入"))
    }

    @Test fun missingCachedWorldLeavesAResultInsteadOfGettingStuckWriting() {
        start()
        val save = manager.state.value.builtins.single { it.edition == GameEdition.CHINESE }
        main { manager.prepareBuiltins(setOf(save.asset)) }; waitFor()
        main { manager.refresh() }; waitFor()
        main { manager.reviewImport() }
        manager.state.value.pending.single().file.delete()
        main { manager.confirmImport() }; waitFor()
        assertEquals(0, writes)
        assertEquals(ImportStep.RESULT, manager.state.value.importStep)
        assertTrue(manager.state.value.pending.isEmpty())
        assertTrue(manager.state.value.results.any { it.error != null })
    }

    @Test fun rememberedAccountIsRevalidatedOnManagerRecreation() {
        start(); accountIds = listOf("53399", "98765")
        main { manager.edition(GameEdition.CHINESE) }; waitFor()
        main { manager.account("98765") }; waitFor()
        start()
        assertEquals(GameEdition.CHINESE, manager.state.value.edition)
        assertEquals("98765", manager.state.value.account)
        assertFalse(manager.state.value.loaded)
        accountIds = listOf("53399", "11111")
        main { manager.refresh() }; waitFor()
        assertEquals("", manager.state.value.account)
        assertFalse(manager.state.value.hasTarget)
    }

    @Test fun cancellingExportPreservesSelectionAndAllowsRetry() {
        start()
        storage[SaveTarget(GameEdition.INTERNATIONAL)] = mutableMapOf("Keep.plr" to byteArrayOf(1, 2, 3))
        main { manager.refresh() }; waitFor()
        main { manager.toggle("Keep.plr") }
        var launches = 0
        main { manager.prepareExport { launches++ } }; waitFor()
        assertTrue(manager.state.value.awaitingExport)
        main { manager.exported(null) }
        assertFalse(manager.state.value.awaitingExport)
        assertEquals(setOf("Keep.plr"), manager.state.value.selected)
        main { manager.prepareExport { launches++ } }; waitFor()
        assertEquals(2, launches)
        main { manager.exported(null) }
    }
}
