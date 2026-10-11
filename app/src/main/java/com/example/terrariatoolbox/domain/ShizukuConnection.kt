package com.example.terrariatoolbox.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ShizukuPhase { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, DENIED, CONNECTING, CONNECTED, DISCONNECTED, TIMED_OUT, UNSUPPORTED }
data class ShizukuState(
    val phase: ShizukuPhase = ShizukuPhase.NOT_INSTALLED,
    val installedVersion: String? = null, val apiVersion: Int? = null, val uid: Int? = null,
    val message: String = "未安装 Shizuku，请先查看启动说明。",
)
data class ShizukuEnvironment(val installedVersion: String?, val running: Boolean, val api: Int? = null,
    val uid: Int? = null, val granted: Boolean = false, val denied: Boolean = false, val mainUser: Boolean = true)
data class TargetProbe(val path: String, val uid: Int, val size: Long, val writable: Boolean)
interface RemoteConfigFiles {
    val alive: Boolean
    fun probe(): TargetProbe
    fun read(): ByteArray
    fun overwrite(bytes: ByteArray, expectedBeforeHash: String)
}
interface ShizukuRuntime {
    fun inspect(): ShizukuEnvironment
    fun listen(changed: () -> Unit, died: () -> Unit, permission: (Boolean) -> Unit)
    fun requestPermission()
    fun bind(connected: (RemoteConfigFiles) -> Unit, disconnected: () -> Unit)
    fun unbind()
    fun close()
}
fun interface ScheduledCancellation { fun cancel() }

/** Runtime callbacks and public methods are serialized on the main thread; file RPCs run on IO. */
class ShizukuConnection(private val runtime: ShizukuRuntime,
    private val scheduleTimeout: (() -> Unit) -> ScheduledCancellation) {
    private val mutable = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = mutable
    @Volatile private var remote: RemoteConfigFiles? = null
    private var timeout: ScheduledCancellation? = null
    private var generation = 0
    private var requested = false
    private var closed = false
    init {
        runtime.listen({ check() }, { disconnected() }, { allowed ->
            if (!closed) {
                if (allowed && requested) connect(false)
                else if (!allowed) { release(); mutable.value = mutable.value.copy(phase = ShizukuPhase.DENIED, message = "Shizuku 授权被拒绝，请在 Shizuku 中调整授权后重试。") }
            }
        })
        check()
    }
    private fun inspect(): ShizukuEnvironment? = try { runtime.inspect() } catch (_: Exception) {
        release(); mutable.value = mutable.value.copy(phase = ShizukuPhase.DISCONNECTED, message = "无法检查 Shizuku，请打开 Shizuku 确认已启动后重试。"); null
    }
    private fun unavailable(env: ShizukuEnvironment): ShizukuState? {
        val base = ShizukuState(installedVersion = env.installedVersion, apiVersion = env.api, uid = env.uid)
        return when {
            env.installedVersion == null -> base
            !env.running -> base.copy(phase = ShizukuPhase.NOT_RUNNING, message = "Shizuku 未启动。请通过无线调试启动后返回。")
            !env.mainUser || env.uid != 2000 || (env.api ?: 0) < 13 -> base.copy(phase = ShizukuPhase.UNSUPPORTED,
                message = "本版仅验证主用户、Shizuku API 13+ 和无线调试 shell UID 2000。")
            !env.granted -> base.copy(phase = if (env.denied) ShizukuPhase.DENIED else ShizukuPhase.NEEDS_PERMISSION,
                message = if (env.denied) "Shizuku 授权被拒绝，请在 Shizuku 中调整授权后重试。" else "Shizuku 已启动，点击连接以授权工具箱。")
            else -> null
        }
    }
    fun check() {
        if (closed) return
        val env = inspect() ?: return
        val unavailable = unavailable(env)
        if (unavailable != null) { release(); mutable.value = unavailable }
        else if (remote?.alive != true && mutable.value.phase != ShizukuPhase.CONNECTING) {
            mutable.value = ShizukuState(ShizukuPhase.DISCONNECTED, env.installedVersion, env.api, env.uid, "已授权，请连接 Shizuku 文件服务。")
        }
    }
    fun connect(askPermission: Boolean = true) {
        if (closed) return
        requested = true
        val env = inspect() ?: return
        val unavailable = unavailable(env)
        if (unavailable != null) {
            release(); mutable.value = unavailable
            if (askPermission && unavailable.phase == ShizukuPhase.NEEDS_PERMISSION) {
                try { runtime.requestPermission() } catch (_: Exception) { disconnected() }
            }
            return
        }
        if (remote?.alive == true) { check(); return }
        if (mutable.value.phase == ShizukuPhase.CONNECTING) return
        release()
        val attempt = generation
        mutable.value = ShizukuState(ShizukuPhase.CONNECTING, env.installedVersion, env.api, env.uid, "正在连接 Shizuku 文件服务…")
        timeout = scheduleTimeout {
            if (attempt == generation && !closed) {
                release(); mutable.value = mutable.value.copy(phase = ShizukuPhase.TIMED_OUT, message = "连接超过 10 秒，已释放连接。请确认 Shizuku 已启动后重试。")
            }
        }
        try {
            runtime.bind({ service ->
                if (attempt == generation && !closed) {
                    timeout?.cancel(); timeout = null
                    remote = service
                    mutable.value = mutable.value.copy(phase = ShizukuPhase.CONNECTED, message = "文件服务已连接；读取和写回能力仍需实际验证。")
                }
            }, { if (attempt == generation && !closed) disconnected() })
        } catch (_: Exception) { disconnected() }
    }
    private fun release() {
        generation++; remote = null; timeout?.cancel(); timeout = null
        runCatching { runtime.unbind() }
    }
    private fun disconnected() {
        if (closed) return
        release(); mutable.value = mutable.value.copy(phase = ShizukuPhase.DISCONNECTED, message = "Shizuku 服务已断开，请重新启动并连接。若有未完成写入，暂勿启动游戏。")
    }
    fun files(): RemoteConfigFiles {
        if (closed) throw WriteNotStartedException("Shizuku 连接已关闭。")
        val env = runtime.inspect()
        if (unavailable(env) != null) throw WriteNotStartedException("Shizuku 授权或服务已失效，请重新连接。")
        return remote?.takeIf { it.alive } ?: throw WriteNotStartedException("Shizuku 文件服务未连接，请先连接。")
    }
    fun close() { closed = true; requested = false; release(); runtime.close() }
}
