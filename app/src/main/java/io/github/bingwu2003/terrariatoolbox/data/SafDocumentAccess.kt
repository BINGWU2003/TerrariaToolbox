package io.github.bingwu2003.terrariatoolbox.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.bingwu2003.terrariatoolbox.domain.*
import java.io.ByteArrayOutputStream

class SafDocumentAccess(private val context: Context) : DocumentAccess {
    private val resolver = context.contentResolver
    private fun hasWriteGrant(uri: Uri): Boolean = context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    override fun read(uri: String): ByteArray {
        try {
            val input = resolver.openInputStream(Uri.parse(uri)) ?: throw ToolException("提供方未返回可读取的文件。")
            return input.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_CONFIG_BYTES) throw ToolException("文件超过 8 MiB，无法处理。")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: ToolException) { throw error }
        catch (_: SecurityException) { throw ToolException("文件授权已失效或被拒绝，请重新授权。") }
        catch (_: Exception) { throw ToolException("目标不存在或不可读取，请检查位置并重新选择。") }
    }

    override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) {
        if (expectedBeforeHash != null) {
            val actual = try { contentHash(read(uri)) } catch (_: Exception) {
                throw WriteNotStartedException("写入前无法读取目标，原文件未写入。")
            }
            if (actual != expectedBeforeHash) throw WriteNotStartedException("源文件发生变化，已取消写入。")
        }
        try {
            // Do not fall back to ambiguous "w", which some providers implement without truncation.
            val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "rwt")
                ?: throw ToolException("提供方不支持覆盖截断写入。")
            // checkError is required for providers backed by a reliable pipe/socket.
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
                descriptor.checkError()
            }
        } catch (_: Exception) { throw ToolException("提供方拒绝写入，或写入／关闭未完成。", true) }
    }

    override fun identity(uri: String): String {
        val value = Uri.parse(uri)
        val canonical = runCatching { resolver.canonicalize(value) }.getOrNull() ?: value
        val id = runCatching { DocumentsContract.getDocumentId(canonical) }.getOrNull()
        return if (id != null) "${canonical.authority}|$id" else canonical.toString()
    }

    fun select(grantUri: Uri, resultFlags: Int, directory: Boolean): SourceInfo {
        if (grantUri.scheme != "content") throw ToolException("只支持系统文件选择器返回的 content URI。")
        val target = if (directory) findConfig(grantUri) else grantUri
        val metadata = describe(target)
        if (metadata.name != "config.json") throw ToolException("请选择名为 config.json 的配置文件；未修改任何文件。")
        val granted = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && granted != 0) {
            runCatching { resolver.takePersistableUriPermission(grantUri, granted) }
        }
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == grantUri }
        val documentId = runCatching { DocumentsContract.getDocumentId(target) }.getOrDefault("")
        val isTarget = target.authority == "com.android.externalstorage.documents" && documentId == TARGET_DOCUMENT_ID
        return SourceInfo(target.toString(), grantUri.toString(), identity(target.toString()), target.authority.orEmpty(),
            documentId, metadata.name, if (directory) "目录授权" else "文件授权",
            (granted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 || permission?.isWritePermission == true) && metadata.writable && hasWriteGrant(target),
            permission?.isReadPermission == true, isTarget)
    }

    fun refresh(source: SourceInfo): SourceInfo {
        val metadata = describe(Uri.parse(source.uri))
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == source.grantUri }
        return source.copy(writable = source.writable && metadata.writable && hasWriteGrant(Uri.parse(source.uri)),
            persisted = permission?.isReadPermission == true)
    }

    fun importSource(uri: Uri, resultFlags: Int): SourceInfo {
        if (uri.scheme != "content") throw ToolException("请选择系统文件入口返回的配置副本。")
        if (resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val name = describe(uri).name
        return SourceInfo(uri.toString(), uri.toString(), identity(uri.toString()), uri.authority.orEmpty(),
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault(""), name, "导入副本",
            false, resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }, false)
    }

    data class Metadata(val name: String, val writable: Boolean)

    fun describe(uri: Uri): Metadata {
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
            return resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw ToolException("所选文件不存在，请重新选择。")
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val flagIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                Metadata(if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "未知文件名",
                    flagIndex >= 0 && cursor.getLong(flagIndex) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong() != 0L)
            } ?: throw ToolException("无法获得文件来源，请重新选择。")
        } catch (error: ToolException) { throw error }
        catch (_: SecurityException) { throw ToolException("文件授权已失效或被拒绝，请重新授权。") }
        catch (_: Exception) { throw ToolException("提供方无法返回文件信息，请换一个授权入口。") }
    }

    private fun findConfig(tree: Uri): Uri {
        try {
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
            val matches = mutableListOf<Uri>()
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
                while (it.moveToNext()) {
                    if (it.getString(1) == "config.json" && it.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) {
                        matches.add(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)))
                    }
                }
            } ?: throw ToolException("目录无法读取，请尝试文件授权入口。")
            if (matches.size != 1) throw ToolException("所选目录未找到唯一的 config.json，请选择配置所在目录。")
            return matches.single()
        } catch (error: ToolException) { throw error }
        catch (_: Exception) { throw ToolException("该目录受系统限制或授权无效，请尝试文件入口。") }
    }
}
