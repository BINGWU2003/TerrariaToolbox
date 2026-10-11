package com.example.terrariatoolbox

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.example.terrariatoolbox.domain.*
import com.example.terrariatoolbox.feature.saves.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream

class SaveManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val storage = mutableMapOf<SaveTarget, MutableMap<String, ByteArray>>()
    private var accountIds = listOf("53399")
    private var writes = 0
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
    private fun start() { main { manager = SaveManager(app, { remote }, scope) }; waitFor { manager.state.value.builtins.size == 5 } }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(condition: () -> Boolean = { !manager.state.value.busy }) {
        val end = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(20) }
        fail("Timed out: ${manager.state.value}")
    }
    @After fun cleanup() { scope.cancel() }
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
        start(); main { manager.edition(GameEdition.CHINESE) }; waitFor()
        val target = SaveTarget(GameEdition.CHINESE, "53399")
        val existing = byteArrayOf(3, 2, 1)
        storage[target] = mutableMapOf("vor.PLR" to existing)
        val assets = manager.state.value.builtins
        main { manager.prepareBuiltins(assets.map { it.asset }.toSet()) }; waitFor()
        assertEquals(0, writes)
        assertEquals(5, manager.state.value.pending.size)
        assertEquals(target, manager.state.value.pendingTarget)
        main { manager.confirmImport() }; waitFor()
        assertEquals(5, writes)
        assertArrayEquals(existing, storage.getValue(target).getValue("vor.PLR"))
        assertEquals("Vor (1).plr", manager.state.value.results.single { it.original == "Vor.plr" }.savedAs)
        assets.forEach { asset ->
            val name = manager.state.value.results.single { it.original == asset.name }.savedAs!!
            assertArrayEquals(app.assets.open(asset.asset).use { it.readBytes() }, storage.getValue(target).getValue(name))
        }
        assertTrue(manager.state.value.pending.isEmpty())
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
}
