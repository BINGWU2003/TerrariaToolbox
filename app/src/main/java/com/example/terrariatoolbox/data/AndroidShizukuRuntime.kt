package com.example.terrariatoolbox.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import com.example.terrariatoolbox.data.shizuku.*
import com.example.terrariatoolbox.domain.*
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
const val SHIZUKU_GUIDE = "https://shizuku.rikka.app/zh-hans/guide/setup/"

class AndroidShizukuRuntime(private val context: Context) : ShizukuRuntime {
    private val handler = Handler(Looper.getMainLooper())
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ConfigUserService::class.java))
        .daemon(false).processNameSuffix("config_access").tag("terraria-fixed-config").version(2)
    private var binding: ServiceConnection? = null
    private var received: Shizuku.OnBinderReceivedListener? = null
    private var dead: Shizuku.OnBinderDeadListener? = null
    private var permission: Shizuku.OnRequestPermissionResultListener? = null
    override fun inspect(): ShizukuEnvironment {
        val version = try { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0).versionName ?: "未知版本" }
            catch (_: PackageManager.NameNotFoundException) { null }
        if (version == null || !Shizuku.pingBinder()) return ShizukuEnvironment(version, false)
        return ShizukuEnvironment(version, true, Shizuku.getVersion(), Shizuku.getUid(),
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
            Shizuku.shouldShowRequestPermissionRationale(), Process.myUid() / 100000 == 0)
    }
    override fun listen(changed: () -> Unit, died: () -> Unit, permission: (Boolean) -> Unit) {
        received = Shizuku.OnBinderReceivedListener { handler.post { changed() } }.also { Shizuku.addBinderReceivedListenerSticky(it, handler) }
        dead = Shizuku.OnBinderDeadListener { died() }.also { Shizuku.addBinderDeadListener(it, handler) }
        this.permission = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == 701) permission(result == PackageManager.PERMISSION_GRANTED)
        }.also { Shizuku.addRequestPermissionResultListener(it, handler) }
    }
    override fun requestPermission() { Shizuku.requestPermission(701) }
    override fun bind(connected: (RemoteConfigFiles) -> Unit, disconnected: () -> Unit) {
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) disconnected() else connected(BinderConfigFiles(IConfigFileService.Stub.asInterface(service)))
            }
            override fun onServiceDisconnected(name: ComponentName?) { disconnected() }
            override fun onBindingDied(name: ComponentName?) { disconnected() }
            override fun onNullBinding(name: ComponentName?) { disconnected() }
        }
        binding = callback
        Shizuku.bindUserService(args, callback)
    }
    override fun unbind() {
        val callback = binding ?: return
        binding = null
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, callback, true)
    }
    override fun close() {
        received?.let { Shizuku.removeBinderReceivedListener(it) }
        dead?.let { Shizuku.removeBinderDeadListener(it) }
        permission?.let { Shizuku.removeRequestPermissionResultListener(it) }
    }
}

fun createShizukuConnection(context: Context): ShizukuConnection {
    val handler = Handler(Looper.getMainLooper())
    return ShizukuConnection(AndroidShizukuRuntime(context)) { action ->
        val callback = Runnable(action)
        handler.postDelayed(callback, 10_000)
        ScheduledCancellation { handler.removeCallbacks(callback) }
    }
}

class BinderConfigFiles(private val remote: IConfigFileService) : RemoteConfigFiles {
    override val alive get() = remote.asBinder().isBinderAlive
    private fun failure(error: Exception, openingWrite: Boolean = false): Nothing {
        val code = if (error is IllegalArgumentException) Regex("^FILE_ERROR:([1-6])$").matchEntire(error.message.orEmpty())?.groupValues?.get(1)?.toInt() else null
        if (code != null) {
            val message = when (code) {
                FixedConfigFile.MISSING -> "目标配置不存在，请先运行游戏生成配置并完全退出。"
                FixedConfigFile.NOT_REGULAR -> "目标不是普通文件或为符号链接，禁止操作。"
                FixedConfigFile.TOO_LARGE -> "配置超过 8 MiB，已中止操作。"
                FixedConfigFile.HASH_CHANGED -> "源文件发生变化，已取消写入。请完全退出游戏后刷新。"
                FixedConfigFile.ACCESS -> "Shizuku 无法访问目标配置，请检查系统限制和文件权限。"
                else -> "Shizuku 文件操作未完成；如有未完成写入，暂勿启动游戏。"
            }
            if (openingWrite && code != FixedConfigFile.IO) throw WriteNotStartedException(message)
            throw ToolException(message, openingWrite)
        }
        throw ToolException("Shizuku 服务、授权或文件操作已失效，请重新连接；如有未完成写入，暂勿启动游戏。", openingWrite)
    }
    override fun probe(): TargetProbe = try {
        val result = remote.probe()
        TargetProbe(result.getString("path").orEmpty(), result.getInt("uid", -1), result.getLong("size"), result.getBoolean("writable"))
    } catch (error: Exception) { failure(error) }
    override fun read(): ByteArray = try {
        ParcelFileDescriptor.AutoCloseInputStream(remote.openRead()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_CONFIG_BYTES) throw ToolException("配置超过 8 MiB，无法处理。")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (error: ToolException) { throw error }
    catch (error: Exception) { failure(error) }
    override fun overwrite(bytes: ByteArray, expectedBeforeHash: String) {
        val descriptor = try { remote.openOverwrite(expectedBeforeHash) } catch (error: Exception) { failure(error, true) }
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes); output.flush(); descriptor.fileDescriptor.sync(); descriptor.checkError()
            }
        } catch (_: Exception) { throw ToolException("Shizuku 写入、同步或关闭未完成，暂勿启动游戏，请检查或恢复备份。", true) }
    }
}
