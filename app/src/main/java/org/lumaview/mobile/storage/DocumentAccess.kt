package org.lumaview.mobile.storage

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DocumentEntry(val uri:Uri,val name:String,val directory:Boolean,val bytes:Long?)
class ReadLease(val uri:Uri,val name:String,val descriptor:ParcelFileDescriptor,val bytes:Long?,val cached:File?=null):AutoCloseable {
 val fd get()=descriptor.fd
 private val closed=AtomicBoolean(false)
 override fun close(){if(closed.compareAndSet(false,true)){descriptor.close();cached?.delete()}}
}
/** File picker grants only. Never resolve content:// URIs into guessed filesystem paths. */
class DocumentAccess(private val context:Context) {
 private val io=Executors.newSingleThreadExecutor();private val ui=Handler(Looper.getMainLooper());private val next=AtomicLong()
 private val cancelled=ConcurrentHashMap<Long,AtomicBoolean>()
 fun rememberGrant(uri:Uri,flags:Int){
  if(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0)try {context.contentResolver.takePersistableUriPermission(uri,flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))}catch(_:SecurityException){}
 }
 fun cancel(id:Long){cancelled[id]?.set(true)}
 fun openRead(uri:Uri,allowCache:Boolean=false,reply:(Result<ReadLease>)->Unit):Long {
  val id=next.incrementAndGet();val stop=AtomicBoolean();cancelled[id]=stop
  io.execute {
   val result=runCatching {
    require(uri.scheme=="content"||uri.scheme=="file"){"只接受系统授权的本地媒体文档"}
    val p=if(uri.scheme=="file")ParcelFileDescriptor.open(File(requireNotNull(uri.path)),ParcelFileDescriptor.MODE_READ_ONLY)else context.contentResolver.openFileDescriptor(uri,"r")?:error("无法打开文件，请重新选择")
    var owned:ParcelFileDescriptor?=p;var temporary:File?=null
    try {
     val name=displayName(uri);val size=p.statSize.takeIf{it>=0}
     val seekable=try{Os.lseek(p.fileDescriptor,0,OsConstants.SEEK_CUR);true}catch(_:Exception){false}
     if(stop.get())error("已取消")
     if(seekable){owned=null;ReadLease(uri,name,p,size)}else {
      check(allowCache){"CACHE_CONSENT_REQUIRED"}
      temporary=File.createTempFile("input-",".media",context.cacheDir)
      // AutoCloseInputStream owns this FD. Cache only after explicit user consent.
      ParcelFileDescriptor.AutoCloseInputStream(p).use {input->owned=null;FileOutputStream(temporary).use{out->
       val buffer=ByteArray(256*1024)
       while(true){if(stop.get())error("已取消");check(context.cacheDir.usableSpace>256L*1024*1024+buffer.size){"空间不足，需保留 256 MiB"};val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)}
       out.fd.sync()
      }}
      val copy=ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY)
      ReadLease(uri,name,copy,temporary.length(),temporary).also{temporary=null}
     }
    }finally{owned?.close();temporary?.delete()}
   }
   cancelled.remove(id)
   ui.post{if(stop.get()){result.getOrNull()?.close();reply(Result.failure(IOException("已取消")))}else reply(result)}
  };return id
 }
 fun displayName(uri:Uri):String {
  return try{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}?:uri.lastPathSegment?:"视频"}catch(_:Exception){"视频"}
 }
 fun listChildren(tree:Uri,parent:String?=null,reply:(Result<List<DocumentEntry>>)->Unit):Long {
  val id=next.incrementAndGet();val stop=AtomicBoolean();cancelled[id]=stop
  io.execute{
   val result=runCatching {
    val root=parent?:DocumentsContract.getTreeDocumentId(tree)
    val query=DocumentsContract.buildChildDocumentsUriUsingTree(tree,root)
    val fields=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE)
    val rows=ArrayList<DocumentEntry>()
    context.contentResolver.query(query,fields,null,null,null)?.use {c->while(c.moveToNext()){
     if(stop.get())error("已取消")
     val dir=c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR
     if(dir||c.getString(2)?.startsWith("video/")==true||c.getString(1).substringAfterLast('.').lowercase() in setOf("mkv","mp4","mov","avi","webm","m4v","ts"))rows.add(DocumentEntry(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)),c.getString(1),dir,if(c.isNull(3))null else c.getLong(3)))
    }}?:error("无法读取文件夹，请重新授权")
    rows.sortedWith(compareByDescending<DocumentEntry>{it.directory}.thenBy{it.name.lowercase()})
   };cancelled.remove(id);ui.post{reply(result)}
  };return id
 }
}
