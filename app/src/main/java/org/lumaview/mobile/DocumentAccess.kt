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
    fun sameDocument(first: Uri?, second: Uri): Boolean {
        if(first==null)return false
        if(first==second)return true
        if(first.authority!=second.authority)return false
        val a=runCatching { DocumentsContract.getDocumentId(first) }.getOrNull()
        val b=runCatching { DocumentsContract.getDocumentId(second) }.getOrNull()
        require(a!=null && b!=null) { "无法确认同一提供方的文档身份，已停止保存" }
        return a==b
    }
    fun commit(file: File, uri: Uri, source: Uri?, cancelled:()->Boolean = {false}) {
        require(!sameDocument(source,uri)) { "目标与源文档相同，已停止保存" }
        // ACTION_CREATE_DOCUMENT must return a new empty document. Refuse nonempty targets.
        val confirmedEmpty=context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { c ->
            c.moveToFirst() && !c.isNull(0) && c.getLong(0)==0L
        } ?: false
        require(confirmedEmpty) { "无法确认目标是空的新文档，已停止保存；暂存文件已保留" }
        try {
            context.contentResolver.openOutputStream(uri,"w").use { dst ->
                requireNotNull(dst) { "无法写入新文档" }
                file.inputStream().use { src ->
                    val buffer=ByteArray(1024*1024)
                    while(true){check(!cancelled()) { "保存已取消" };val n=src.read(buffer);if(n<0)break;dst.write(buffer,0,n)}
                    check(!cancelled()) { "保存已取消" };dst.flush()
                }
            }
        } catch(e:Exception) {
            val deleted=runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }.getOrDefault(false)
            throw IllegalStateException("保存失败${if(deleted) "，半成品已删除" else "，请检查并删除目标中的半成品"}",e)
        }
    }
}
