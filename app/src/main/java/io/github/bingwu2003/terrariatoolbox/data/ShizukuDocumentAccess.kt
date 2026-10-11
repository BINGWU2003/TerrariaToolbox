package io.github.bingwu2003.terrariatoolbox.data

import io.github.bingwu2003.terrariatoolbox.domain.*

class ShizukuDocumentAccess(private val connection: ShizukuConnection) : DocumentAccess {
    private fun requireUri(uri: String) {
        if (uri != SHIZUKU_URI) throw WriteNotStartedException("Shizuku 仅支持固定的游戏配置来源。")
    }
    override fun identity(uri: String): String { requireUri(uri); return SHIZUKU_IDENTITY }
    override fun read(uri: String): ByteArray { requireUri(uri); return connection.files().read() }
    override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) {
        requireUri(uri)
        if (bytes.size > MAX_CONFIG_BYTES || expectedBeforeHash == null) throw WriteNotStartedException("候选配置过大或缺少源文件校验，已取消写入。")
        connection.files().overwrite(bytes, expectedBeforeHash)
    }
    fun select(): SourceInfo {
        val probe = connection.files().probe()
        if (probe.uid != 2000 || probe.path != TARGET_PATH) throw ToolException("Shizuku 服务身份或目标路径不匹配，禁止写入。")
        return SourceInfo(SHIZUKU_URI, SHIZUKU_URI, SHIZUKU_IDENTITY, "Shizuku shell UID 2000", TARGET_PATH,
            "config.json", "Shizuku 授权", probe.writable, false, true, confirmed = true, accessMethod = AccessMethod.SHIZUKU)
    }
    fun refresh(source: SourceInfo): SourceInfo {
        if (source.uri != SHIZUKU_URI || source.identity != SHIZUKU_IDENTITY || source.accessMethod != AccessMethod.SHIZUKU)
            throw ToolException("保存的 Shizuku 来源不匹配，请重新连接。")
        return select().copy(lastVerifiedAt = source.lastVerifiedAt)
    }
}
