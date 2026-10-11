package io.github.bingwu2003.terrariatoolbox

import io.github.bingwu2003.terrariatoolbox.domain.*
import io.github.bingwu2003.terrariatoolbox.data.ShizukuDocumentAccess
import org.junit.Assert.*
import org.junit.Test

private class FakeRuntime : ShizukuRuntime {
    var env = ShizukuEnvironment("13.6.0", true, 13, 2000, true)
    var changed: () -> Unit = {}
    var died: () -> Unit = {}
    var permission: (Boolean) -> Unit = {}
    var connected: (RemoteConfigFiles) -> Unit = {}
    var requests = 0
    var binds = 0
    var unbinds = 0
    var reads = 0
    var probe = TargetProbe(TARGET_PATH, 2000, 14, true)
    val files = object : RemoteConfigFiles {
        override val alive = true
        override fun probe() = probe
        override fun read(): ByteArray { reads++; return "{\"Language\":1}".toByteArray() }
        override fun overwrite(bytes: ByteArray, expectedBeforeHash: String) = Unit
    }
    override fun inspect() = env
    override fun listen(changed: () -> Unit, died: () -> Unit, permission: (Boolean) -> Unit) {
        this.changed = changed; this.died = died; this.permission = permission
    }
    override fun requestPermission() { requests++ }
    override fun bind(connected: (RemoteConfigFiles) -> Unit, disconnected: () -> Unit) { binds++; this.connected = connected }
    override fun unbind() { unbinds++ }
    override fun close() = Unit
}

class ShizukuConnectionTest {
    private val runtime = FakeRuntime()
    private var timedOut: () -> Unit = {}
    private var timerCancelled = false
    private val connection = ShizukuConnection(runtime) { callback ->
        timedOut = callback; timerCancelled = false; ScheduledCancellation { timerCancelled = true }
    }
    private fun connect() { connection.connect(); runtime.connected(runtime.files) }
    @Test fun missingStoppedUnsupportedUidAndSecondaryUserNeverBind() {
        for (env in listOf(ShizukuEnvironment(null, false), ShizukuEnvironment("13", false),
            runtime.env.copy(uid = 0), runtime.env.copy(api = 12), runtime.env.copy(mainUser = false))) {
            runtime.env = env; connection.connect()
            assertNotEquals(ShizukuPhase.CONNECTED, connection.state.value.phase)
        }
        assertEquals(0, runtime.binds)
    }
    @Test fun permissionIsOnlyRequestedOnExplicitConnectionAndDenialDoesNotRetry() {
        runtime.env = runtime.env.copy(granted = false)
        connection.check(); connection.connect(false)
        assertEquals(0, runtime.requests)
        connection.connect(); assertEquals(1, runtime.requests)
        runtime.env = runtime.env.copy(denied = true)
        runtime.permission(false)
        assertEquals(ShizukuPhase.DENIED, connection.state.value.phase)
        connection.connect()
        assertEquals(1, runtime.requests); assertEquals(0, runtime.binds)
    }
    @Test fun permissionGrantBindsWithoutPerformingAnyFileOperation() {
        runtime.env = runtime.env.copy(granted = false)
        connection.connect()
        runtime.env = runtime.env.copy(granted = true)
        runtime.permission(true); runtime.connected(runtime.files)
        assertEquals(ShizukuPhase.CONNECTED, connection.state.value.phase)
        assertEquals(0, runtime.reads); assertTrue(timerCancelled)
    }
    @Test fun timeoutReleasesBindingAndIgnoresLateCallbackThenAllowsRetry() {
        connection.connect(); val late = runtime.connected
        timedOut()
        assertEquals(ShizukuPhase.TIMED_OUT, connection.state.value.phase)
        late(runtime.files)
        assertEquals(ShizukuPhase.TIMED_OUT, connection.state.value.phase)
        assertThrows(WriteNotStartedException::class.java) { connection.files() }
        connect(); assertEquals(ShizukuPhase.CONNECTED, connection.state.value.phase)
    }
    @Test fun deathAndPermissionRevocationInvalidateConnectedFiles() {
        connect(); runtime.died()
        assertEquals(ShizukuPhase.DISCONNECTED, connection.state.value.phase)
        assertThrows(WriteNotStartedException::class.java) { connection.files() }
        connect(); runtime.env = runtime.env.copy(granted = false)
        assertThrows(WriteNotStartedException::class.java) { connection.files() }
        connection.check(); assertEquals(ShizukuPhase.NEEDS_PERMISSION, connection.state.value.phase)
    }
    @Test fun sourceIsConfirmedOnlyAfterExactServiceIdentityAndPathAreValidated() {
        connect(); val access = ShizukuDocumentAccess(connection)
        val source = access.select()
        assertTrue(source.confirmed); assertFalse(source.persisted)
        assertEquals(AccessMethod.SHIZUKU, source.accessMethod)
        assertTrue(access.refresh(source.copy(confirmed = true)).confirmed)
        assertTrue(access.refresh(source.copy(confirmed = false)).confirmed)
        runtime.probe = runtime.probe.copy(uid = 0)
        assertThrows(ToolException::class.java) { access.select() }
        runtime.probe = runtime.probe.copy(uid = 2000, path = "/another/config.json")
        assertThrows(ToolException::class.java) { access.select() }
        assertThrows(ToolException::class.java) { access.read(SHIZUKU_URI + "/../other") }
    }
    @Test fun closeCancelsCallbacksAndCannotReconnectOrWrite() {
        connection.connect(); val late = runtime.connected
        connection.close(); late(runtime.files); runtime.permission(true)
        connection.connect()
        assertThrows(WriteNotStartedException::class.java) { connection.files() }
        assertEquals(1, runtime.binds)
    }
}
