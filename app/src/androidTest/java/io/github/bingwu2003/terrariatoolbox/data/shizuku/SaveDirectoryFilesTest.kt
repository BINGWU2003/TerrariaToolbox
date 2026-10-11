package io.github.bingwu2003.terrariatoolbox.data.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.data.BinderConfigFiles
import io.github.bingwu2003.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** All paths are app-private fixtures; these tests never use a real game's directory. */
class SaveDirectoryFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "save-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    private val intl = File(root, "intl").apply { mkdirs() }
    private val cn = File(root, "cn").apply { mkdirs() }
    private val engine = SaveDirectoryFiles(intl, cn)
    private val original = byteArrayOf(0, 5, 6, -1, 10)
    private fun input() = File(root, UUID.randomUUID().toString()).apply { writeBytes(original) }
    private fun import(edition: Int = 0, account: String = "", name: String = "Vor.plr", hash: String? = null): String {
        val file = input()
        return engine.importSave(edition, account, name, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file.length(), hash ?: saveFileHash(file)).getString("name")!!
    }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun duplicateImportRenamesIgnoringCaseAndPreservesOriginalAndSidecars() {
        assertEquals("Vor.plr", import())
        val originalFile = File(intl, "Players/Vor.plr")
        val originalTime = originalFile.lastModified()
        val sidecar = File(intl, "Players/Vor.plr.bak").apply { writeText("backup") }
        assertEquals("vor (1).PLR", import(name = "vor.PLR"))
        assertEquals("Vor (2).plr", import())
        assertArrayEquals(original, originalFile.readBytes())
        assertEquals(originalTime, originalFile.lastModified())
        assertEquals("backup", sidecar.readText())
        val list = engine.list(0, "").getParcelableArrayList("entries", android.os.Bundle::class.java)!!
        assertEquals(3, list.size)
        ParcelFileDescriptor.AutoCloseInputStream(engine.read(0, "", "Vor (2).plr")).use { assertArrayEquals(original, it.readBytes()) }
    }
    @Test fun accountsAndEditionsAreIsolatedAndOnlyNumericRealDirectoriesAreListed() {
        File(cn, "53399").mkdir(); File(cn, "98765").mkdir(); File(cn, "files").mkdir()
        Os.symlink(intl.absolutePath, File(cn, "888").absolutePath)
        assertEquals(listOf("53399", "98765"), engine.accounts().toList())
        assertEquals("Vor.plr", import(1, "53399")); assertEquals("Vor.plr", import(1, "98765")); assertEquals("Vor.plr", import())
        assertTrue(File(cn, "53399/Players/Vor.plr").exists())
        assertFalse(File(cn, "files/Players").exists())
        assertThrows(IllegalArgumentException::class.java) { import(1, "../intl") }
        assertThrows(IllegalArgumentException::class.java) { import(0, "53399") }
        assertThrows(IllegalArgumentException::class.java) { import(1, "888") }
    }
    @Test fun invalidHashRollsBackOnlyNewFileAndTraversalAndSymlinksAreRejected() {
        import()
        assertThrows(IllegalArgumentException::class.java) { import(hash = "0".repeat(64)) }
        assertFalse(File(intl, "Players/Vor (1).plr").exists())
        assertArrayEquals(original, File(intl, "Players/Vor.plr").readBytes())
        assertThrows(IllegalArgumentException::class.java) { import(name = "../evil.plr") }
        val elsewhere = input()
        Os.symlink(elsewhere.absolutePath, File(intl, "Players/link.plr").absolutePath)
        assertThrows(IllegalArgumentException::class.java) { engine.read(0, "", "link.plr") }
        File(intl, "Worlds").let { Os.symlink(root.absolutePath, it.absolutePath) }
        assertThrows(IllegalArgumentException::class.java) { import(name = "evil.wld") }
        assertFalse(File(root, "evil.wld").exists())
    }
    @Test fun concurrentImportsReserveDifferentNamesWithoutOverwrite() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..8).map { pool.submit<String> { import() } }
            val names = futures.map { it.get() }
            assertEquals(8, names.toSet().size)
            names.forEach { assertArrayEquals(original, File(intl, "Players/$it").readBytes()) }
        } finally { pool.shutdownNow() }
    }
    @Test fun importedLargeFileWorksThroughBinderDescriptorTransport() {
        val binder = object : IConfigFileService.Stub() {
            override fun destroy() = Unit
            override fun probe() = throw UnsupportedOperationException()
            override fun openRead(): ParcelFileDescriptor = throw UnsupportedOperationException()
            override fun openOverwrite(expectedBeforeHash: String): ParcelFileDescriptor = throw UnsupportedOperationException()
            override fun saveAccounts() = engine.accounts()
            override fun listSaves(edition: Int, account: String) = engine.list(edition, account)
            override fun openSaveRead(edition: Int, account: String, name: String) = engine.read(edition, account, name)
            override fun importSave(edition: Int, account: String, name: String, source: ParcelFileDescriptor, size: Long, hash: String) =
                engine.importSave(edition, account, name, ParcelFileDescriptor.dup(source.fileDescriptor), size, hash)
        }
        val remote = BinderConfigFiles(binder).saves()
        val file = File(root, "large").apply { writeBytes(ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }) }
        val target = SaveTarget(GameEdition.INTERNATIONAL)
        assertEquals("All + Items (3).wld", remote.import(target, StagedSave("All + Items (3).wld", file, target.edition, SaveOrigin.USER)))
        val output = File(root, "export")
        output.outputStream().use { remote.read(target, "All + Items (3).wld", it) }
        assertEquals(saveFileHash(file), saveFileHash(output))
        assertEquals(file.length(), remote.list(target).single().size)
    }
}
