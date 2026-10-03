package org.lumaview.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Independent private read-only snapshot: player and exporter never share seek offsets. */
class DocumentAccess(private val context: Context) {
    fun cache(uri: Uri): File {
        val out=File(context.cacheDir,"source-${UUID.randomUUID()}.media")
        try {
            context.contentResolver.openInputStream(uri).use { src ->
                requireNotNull(src) { "文件提供方未返回数据" }
                out.outputStream().use { dst ->
                    val b=ByteArray(1024*1024)
                    while(true) {
                        val n=src.read(b);if(n<0)break
                        if(out.parentFile!!.usableSpace < n+16L*1024*1024)error("缓存空间不足")
                        dst.write(b,0,n)
                    }
                }
            }
            require(out.length()>0) { "文件为空" }
            return out
        } catch(e:Exception) { out.delete();throw e }
    }
    fun persist(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst())c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "本地视频"
    fun children(tree: Uri): List<Pair<Uri,String>> {
        val root=DocumentsContract.getTreeDocumentId(tree)
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,root)
        val result=mutableListOf<Pair<Uri,String>>()
        context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
            while(c.moveToNext()) {
                val mime=c.getString(2)?:""
                if(mime.startsWith("video/") || mime.startsWith("audio/"))result.add(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)) to c.getString(1))
            }
        }
        return result.sortedBy { it.second.lowercase() }
    }
    fun commit(file: File, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri,"w").use { dst ->
                requireNotNull(dst) { "无法写入新文档" };file.inputStream().use { it.copyTo(dst) }
            }
        } catch(e:Exception) {
            val deleted=runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }.getOrDefault(false)
            throw IllegalStateException("保存失败${if(deleted) "，半成品已删除" else "，请检查并删除目标中的半成品"}",e)
        }
    }
}
