package org.lumaview.mobile.storage
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
/** The destination is obtained from ACTION_CREATE_DOCUMENT, never an arbitrary open-file URI. */
object OutputTransaction {
 fun saveNew(context:Context,temp:File,destination:Uri,source:Uri?,stop:AtomicBoolean,progress:(Double)->Unit) {
  require(destination!=source){"不能覆盖源视频"}
  fun docId(u:Uri)=try{DocumentsContract.getDocumentId(u)}catch(_:Exception){null}
  if(source!=null&&source.authority==destination.authority&&docId(source)!=null)require(docId(source)!=docId(destination)){"目标与原文件是同一文档"}
  val resolver=context.contentResolver
  val p=resolver.openFileDescriptor(destination,"rw")?:error("无法写入新文件")
  var safeToRemove=false
  try{
   // Refuse non-empty existing files before any write or truncation.
   require(p.statSize==0L){"提供方未返回可验证的空白新文件，已停止以保护现存内容"}
   if(source!=null)try {resolver.openFileDescriptor(source,"r")?.use{s->val a=Os.fstat(s.fileDescriptor);val b=Os.fstat(p.fileDescriptor);require(a.st_dev!=b.st_dev||a.st_ino!=b.st_ino){"目标别名指向原文件"}}}catch(e:IllegalArgumentException){throw e}catch(_:Exception){}
   safeToRemove=true
   FileInputStream(temp).use{input->ParcelFileDescriptor.AutoCloseOutputStream(p).use{output->
    val buf=ByteArray(256*1024);var bytes=0L
    while(true){check(!stop.get()){ "已取消保存" };val n=input.read(buf);if(n<0)break;output.write(buf,0,n);bytes+=n;progress(bytes.toDouble()/temp.length().coerceAtLeast(1))}
    output.flush();try{output.fd.sync()}catch(_:Exception){}
   }}
  }catch(e:Exception){
   if(safeToRemove){val deleted=try{DocumentsContract.deleteDocument(resolver,destination)}catch(_:Exception){false};if(!deleted)throw IOException("保存未完成，无法清理新建半成品：$destination",e)}
   throw e
  }finally{try{p.close()}catch(_:Exception){}}
 }
}
