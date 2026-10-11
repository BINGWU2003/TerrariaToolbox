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
        .daemon(false).processNameSuffix("config_access").tag("terraria-fixed-config").version(3)
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

class BinderConfigFiles(private val remote: IConfigFileService) : RemoteConfigFiles, RemoteSaveFiles {
    override fun saves(): RemoteSaveFiles = this
    private fun saveFailure(error: Exception): Nothing {
        val message = when (error.message) {
            "SAVE_ERROR:MISSING_ROOT" -> "没有找到该版本的游戏存档目录。请先安装并打开游戏；国服还需要登录账号，再完全退出游戏后刷新。"
            "SAVE_ERROR:INVALID" -> "存档名称、账号或目录异常，已取消操作。请选择 .plr、.wld 或工具箱导出的 ZIP。"
            "SAVE_ERROR:LIMIT" -> "文件过大或数量过多，请分批处理。单个存档支持 64 MB。"
            "SAVE_ERROR:VERIFY" -> "存档校验未通过，本次导入已取消。请检查原文件后重试。"
            "SAVE_ERROR:ACCESS" -> "无法访问游戏存档，请确认 Shizuku 已启动并授权后重试。"
            else -> "存档操作未完成，结果尚未确认。请保持游戏关闭，重新连接并刷新列表后检查。"
        }
        throw ToolException(message)
    }
    override fun accounts(): List<String> = try { remote.saveAccounts().toList() } catch (e: Exception) { saveFailure(e) }
    override fun list(target: SaveTarget): List<GameSave> = try {
        remote.listSaves(target.edition.code, target.account).getParcelableArrayList("entries", Bundle::class.java).orEmpty().map {
            GameSave(it.getString("name").orEmpty(), it.getLong("size"), it.getLong("modified"))
        }
    } catch (e: Exception) { saveFailure(e) }
    override fun read(target: SaveTarget, name: String, output: java.io.OutputStream) {
        try {
            ParcelFileDescriptor.AutoCloseInputStream(remote.openSaveRead(target.edition.code, target.account, name)).use { input ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > MAX_SAVE_BYTES) throw ToolException("存档超过 64 MB，已取消读取。")
                    output.write(buffer, 0, n)
                }
                if (total == 0L) throw ToolException("存档文件为空，已取消读取。")
            }
        } catch (e: ToolException) { throw e } catch (e: Exception) { saveFailure(e) }
    }
    override fun import(target: SaveTarget, save: StagedSave): String = try {
        ParcelFileDescriptor.open(save.file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            remote.importSave(target.edition.code, target.account, save.name, source, save.file.length(), saveFileHash(save.file)).getString("name")
                ?: throw ToolException("没有收到导入结果，请刷新列表检查。")
        }
    } catch (e: ToolException) { throw e } catch (e: Exception) { saveFailure(e) }
    override val alive get() = remote.asBinder().isBinderAlive
    private fun failure(error: Exception, openingWrite: Boolean = false): Nothing {
        val code = if (error is IllegalArgumentException) Regex("^FILE_ERROR:([1-6])$").matchEntire(error.message.orEmpty())?.groupValues?.get(1)?.toInt() else null
        if (code != null) {
            val message = when (code) {
                FixedConfigFile.MISSING -> "还没有找到游戏设置。请先打开一次泰拉瑞亚，再完全退出后重新连接。"
                FixedConfigFile.NOT_REGULAR -> "游戏设置文件的位置异常，已取消操作。"
                FixedConfigFile.TOO_LARGE -> "游戏设置文件过大，已取消操作。"
                FixedConfigFile.HASH_CHANGED -> "游戏设置发生变化，已取消保存。请完全退出游戏后刷新。"
                FixedConfigFile.ACCESS -> "Shizuku 无法打开游戏设置，请确认游戏已安装，并重新启动 Shizuku 后重试。"
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
                if (output.size() + count > MAX_CONFIG_BYTES) throw ToolException("游戏设置文件过大，暂时无法处理。")
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
