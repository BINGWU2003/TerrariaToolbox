package io.github.bingwu2003.terrariatoolbox.domain

class RoutingDocumentAccess(private val saf: DocumentAccess, private val shizuku: DocumentAccess) : DocumentAccess {
    private fun backend(uri: String): DocumentAccess = when {
        uri == SHIZUKU_URI -> shizuku
        uri.startsWith("content://") -> saf
        else -> throw ToolException("文件来源无效，请重新授权。")
    }
    override fun read(uri: String) = backend(uri).read(uri)
    override fun overwrite(uri: String, bytes: ByteArray, expectedBeforeHash: String?) =
        backend(uri).overwrite(uri, bytes, expectedBeforeHash)
    override fun identity(uri: String): String {
        val identity = backend(uri).identity(uri)
        // Shared alias for export protection only. Stored backup identities remain backend-specific.
        return if (identity == "com.android.externalstorage.documents|$TARGET_DOCUMENT_ID") SHIZUKU_IDENTITY else identity
    }
}
