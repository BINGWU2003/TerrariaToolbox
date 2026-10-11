package io.github.bingwu2003.terrariatoolbox.data.shizuku

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bingwu2003.terrariatoolbox.data.*
import io.github.bingwu2003.terrariatoolbox.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class FixedConfigFileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.noBackupFilesDir, "fd-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val target = File(root, "config.json")
    private val original = "{\"Language\":1,\"exact\":12345678901234567890}".toByteArray()
    private val engine = FixedConfigFile(target)
    private fun read() = ParcelFileDescriptor.AutoCloseInputStream(engine.openRead()).use { it.readBytes() }
    private fun write(bytes: ByteArray, hash: String) = ParcelFileDescriptor.AutoCloseOutputStream(engine.openOverwrite(hash)).use {
        it.write(bytes); it.flush(); it.fd.sync()
    }
    @Test fun probeAndHashConflictDoNotChangeOriginalThenOverwriteTruncates() {
        target.writeBytes(original)
        assertTrue(engine.probe().getBoolean("writable"))
        assertArrayEquals(original, read())
        val error = assertThrows(ConfigFileException::class.java) { engine.openOverwrite("0".repeat(64)) }
        assertEquals(FixedConfigFile.HASH_CHANGED, error.errorCode)
        assertArrayEquals(original, target.readBytes())
        val chinese = "{\"Language\":7}".toByteArray()
        write(chinese, contentHash(original))
        assertArrayEquals(chinese, read())
        write(original, contentHash(chinese)); assertArrayEquals(original, read())
    }
    @Test fun missingDirectorySymlinkAndOversizeAreRejectedWithoutCreatingOrTruncating() {
        assertEquals(FixedConfigFile.MISSING, assertThrows(ConfigFileException::class.java) { engine.probe() }.errorCode)
        assertFalse(target.exists())
        target.mkdir()
        assertEquals(FixedConfigFile.NOT_REGULAR, assertThrows(ConfigFileException::class.java) { engine.openRead() }.errorCode)
        target.delete()
        val other = File(root, "original.json").apply { writeBytes(original) }
        Os.symlink(other.absolutePath, target.absolutePath)
        assertEquals(FixedConfigFile.NOT_REGULAR, assertThrows(ConfigFileException::class.java) { engine.openOverwrite(contentHash(original)) }.errorCode)
        assertArrayEquals(original, other.readBytes())
        target.delete()
        RandomAccessFile(target, "rw").use { it.setLength(MAX_CONFIG_BYTES.toLong() + 1) }
        assertEquals(FixedConfigFile.TOO_LARGE, assertThrows(ConfigFileException::class.java) { engine.probe() }.errorCode)
        assertEquals(MAX_CONFIG_BYTES.toLong() + 1, target.length())
    }
    @Test fun owningApplicationCannotCallUserServiceWithoutShellIdentity() {
        assertThrows(SecurityException::class.java) { ConfigUserService(context).probe() }
    }
    private fun transport(failRead: () -> Boolean = { false }, output: ((String) -> ParcelFileDescriptor)? = null): BinderConfigFiles {
        val binder = object : IConfigFileService.Stub() {
            override fun destroy() = Unit
            override fun saveAccounts() = emptyArray<String>()
            override fun listSaves(edition: Int, account: String) = android.os.Bundle()
            override fun openSaveRead(edition: Int, account: String, name: String): ParcelFileDescriptor = throw UnsupportedOperationException()
            override fun importSave(edition: Int, account: String, name: String, source: ParcelFileDescriptor, size: Long, hash: String) = android.os.Bundle()
            override fun probe() = engine.probe()
            override fun openRead(): ParcelFileDescriptor {
                if (failRead()) throw ConfigFileException(FixedConfigFile.ACCESS)
                return engine.openRead()
            }
            override fun openOverwrite(expectedBeforeHash: String) = output?.invoke(expectedBeforeHash) ?: engine.openOverwrite(expectedBeforeHash)
        }
        return BinderConfigFiles(binder)
    }
    @Test fun descriptorsHandleLargeConfigAndBackupsRestoreRawBytes() {
        val bytes = ("{\"Language\":1,\"large\":\"" + "x".repeat(2 * 1024 * 1024) + "\",\"number\":12345678901234567890}").toByteArray()
        target.writeBytes(bytes)
        val transport = transport()
        assertArrayEquals(bytes, transport.read())
        val processor = ConfigProcessor()
        val candidate = processor.chineseCandidate(bytes)
        transport.overwrite(candidate, contentHash(bytes))
        processor.verifyChinese(bytes, transport.read())
        transport.overwrite(bytes, contentHash(candidate)); assertArrayEquals(bytes, transport.read())
    }
    @Test fun syncFailureAndReadbackFailureKeepBackupAndPendingOperation() {
        target.writeBytes(original)
        val pipes = ParcelFileDescriptor.createPipe()
        val syncFailure = transport(output = { pipes[1] })
        val backupRoot = File(root, "backups")
        val backups = FileBackupRepository(backupRoot, AndroidAtomicWriter())
        val journal = FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter())
        val source = SourceInfo(SHIZUKU_URI, SHIZUKU_URI, SHIZUKU_IDENTITY, "test", TARGET_PATH, "config.json", "test", true, false, true, true,
            accessMethod = AccessMethod.SHIZUKU)
        fun documents(remote: RemoteConfigFiles) = object : DocumentAccess {
            override fun read(uri: String) = remote.read()
            override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) = remote.overwrite(bytes, expectedBeforeHash!!)
            override fun identity(uri: String) = SHIZUKU_IDENTITY
        }
        val processor = ConfigProcessor()
        assertTrue(assertThrows(ToolException::class.java) { WriteCoordinator(documents(syncFailure), backups, journal, processor).switchToChinese(source) }.needsRecovery)
        pipes[0].close()
        assertNotNull(journal.load()); assertArrayEquals(original, backups.read(backups.list().first()))
        journal.clear()
        var reads = 0
        val readbackFailure = transport(failRead = { ++reads == 3 })
        assertTrue(assertThrows(ToolException::class.java) { WriteCoordinator(documents(readbackFailure), backups, journal, processor).switchToChinese(source) }.needsRecovery)
        assertNotNull(journal.load())
        WriteCoordinator(documents(transport()), backups, journal, processor).restore(source, backups.list().first())
        assertArrayEquals(original, target.readBytes()); assertNull(journal.load())
    }
    @Test fun descriptorCloseFailureNeverReportsSuccessAndRetainsRecoveryRecord() {
        target.writeBytes(original)
        val closingFailure = transport(output = { hash ->
            object : ParcelFileDescriptor(engine.openOverwrite(hash)) {
                override fun close() {
                    super.close()
                    throw java.io.IOException("controlled close failure")
                }
            }
        })
        val backups = FileBackupRepository(File(root, "backups"), AndroidAtomicWriter())
        val journal = FileOperationJournal(File(root, "pending.json"), AndroidAtomicWriter())
        val source = SourceInfo(SHIZUKU_URI, SHIZUKU_URI, SHIZUKU_IDENTITY, "test", TARGET_PATH,
            "config.json", "test", true, false, true, true, accessMethod = AccessMethod.SHIZUKU)
        val access = object : DocumentAccess {
            override fun read(uri: String) = closingFailure.read()
            override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) =
                closingFailure.overwrite(bytes, expectedBeforeHash!!)
            override fun identity(uri: String) = SHIZUKU_IDENTITY
        }
        val error = assertThrows(ToolException::class.java) {
            WriteCoordinator(access, backups, journal, ConfigProcessor()).switchToChinese(source)
        }
        assertTrue(error.needsRecovery)
        assertNotNull(journal.load())
        assertArrayEquals(original, backups.read(backups.list().single()))
    }
}
