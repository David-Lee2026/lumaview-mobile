"""One-time readable source migration, committed before the native build."""
from pathlib import Path
import hashlib,json
root=Path.cwd()
def edit(path,old,new):
 p=root/path;s=p.read_text();assert old in s,(path,old[:100]);p.write_text(s.replace(old,new))
base='app/src/main/java/org/lumaview/mobile/'
edit(base+'Core.kt',' fun values()=floatArrayOf(', ' fun selectMode(value:Int)=if(value==mode)this else copy(mode=value.coerceIn(0,4),bypass=false,locked=false)\n fun values()=floatArrayOf(')
edit(base+'Core.kt','".%03d",us/1000%1000','".%06d",us%1000000')
edit(base+'ui/PlayerActivity.kt','enhancement=enhancement.copy(mode=i,bypass=false,locked=false);sendEnhancement()','enhancement=enhancement.selectMode(i);sendEnhancement()')
edit('app/src/main/AndroidManifest.xml','android:name="org.lumaview.mobile.ui.PlayerActivity" android:exported="true"','android:name="org.lumaview.mobile.ui.PlayerActivity" android:exported="true" android:launchMode="singleTask"')
edit(base+'ui/PlayerActivity.kt',' private var s=PlayerState();',' private var openRequest=0L;private var openToken=0L\n private var s=PlayerState();')
edit(base+'ui/PlayerActivity.kt',' private fun buildUi(){',''' override fun onNewIntent(incoming:Intent){
  super.onNewIntent(incoming)
  val next=incoming.data
  if(next==null||next.scheme !in listOf("file","content")){message("只接受授权视频文件");return}
  if(busy){message("请先完成或取消当前导出，再打开其他视频");return}
  intent=incoming;access.rememberGrant(next,incoming.flags)
  overlay.cancel();crop=null;userRotation=0;clipRange=null;loop=false;selectionRow.visibility=View.GONE;clipPanel.visibility=View.GONE
  enhancement=enhancement.copy(locked=false);open(next);wake()
 }
 private fun buildUi(){''')
edit(base+'ui/PlayerActivity.kt','private fun open(u:Uri,allowCache:Boolean=false){access.openRead(u,allowCache){result->if(isDestroyed)', 'private fun open(u:Uri,allowCache:Boolean=false){access.cancel(openRequest);val token=++openToken;openRequest=access.openRead(u,allowCache){result->if(isDestroyed||isFinishing||token!=openToken)')
edit(base+'ui/PlayerActivity.kt','" · 区域增强"','" · 区域放大"')
edit(base+'ui/PlayerActivity.kt','LinearLayout.LayoutParams(-1,36.dp)','LinearLayout.LayoutParams(-1,48.dp)')
edit(base+'ui/PlayerActivity.kt','LinearLayout.LayoutParams(-1,42.dp)','LinearLayout.LayoutParams(-1,48.dp)')
edit(base+'ui/PlayerActivity.kt','!s.paused&&!dragging&&now-startAt>5000','!s.paused&&!s.buffering&&hasWindowFocus()&&!dragging&&now-startAt>5000')
edit(base+'ui/PlayerActivity.kt','override fun onStopTrackingTouch(bar:SeekBar){','override fun onStopTrackingTouch(bar:SeekBar){if(touchLocked)return;')
edit(base+'ui/PlayerActivity.kt','if(!isFinishing)AlertDialog','if(!isFinishing&&!isDestroyed)AlertDialog')
edit(base+'ui/PlayerActivity.kt','override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(::session.isInitialized)session.close();if(busy)cancelJob();super.onDestroy()}', 'override fun onDestroy(){if(busy)cancelJob();handler.removeCallbacksAndMessages(null);if(::access.isInitialized)access.close();work.shutdown();if(::session.isInitialized)session.close();dialog?.dismiss();dialog=null;super.onDestroy()}')
edit(base+'storage/DocumentAccess.kt','class DocumentAccess(private val context:Context) {','class DocumentAccess(context:Context):AutoCloseable {\n private val context=context.applicationContext;private val closed=AtomicBoolean(false)')
edit(base+'storage/DocumentAccess.kt',' fun rememberGrant(uri:Uri,flags:Int){',' override fun close(){if(closed.compareAndSet(false,true)){cancelled.values.forEach{it.set(true)};io.shutdown()}}\n fun rememberGrant(uri:Uri,flags:Int){')
edit(base+'storage/DocumentAccess.kt','val id=next.incrementAndGet();val stop=AtomicBoolean();cancelled[id]=stop','val id=next.incrementAndGet();if(closed.get()){ui.post{reply(Result.failure(IOException("文件访问已关闭")))};return id};val stop=AtomicBoolean();cancelled[id]=stop')
edit(base+'storage/DocumentAccess.kt','ui.post{if(stop.get()){','ui.post{if(stop.get()||closed.get()){')
edit(base+'storage/DocumentAccess.kt','};cancelled.remove(id);ui.post{reply(result)}','};cancelled.remove(id);ui.post{if(!closed.get())reply(result)}')
edit(base+'ui/LibraryActivity.kt','if(isFinishing)return@listChildren','if(isFinishing||isDestroyed)return@listChildren')
edit(base+'ui/LibraryActivity.kt',' private val Int.dp',' override fun onDestroy(){if(::access.isInitialized)access.close();super.onDestroy()}\n private val Int.dp')
edit(base+'player/PlayerSession.kt','val outputFrames:Long?=null)','val outputFrames:Long?=null,val buffering:Boolean=false)')
edit(base+'player/PlayerSession.kt','private var initialized=false;private var closed=false','private var initialized=false;private var created=false;@Volatile private var closed=false')
edit(base+'player/PlayerSession.kt','private var generation=0L;','@Volatile private var generation=0L;')
edit(base+'player/PlayerSession.kt','override fun event(eventId:Int){if(eventId==21)worker.post{if(initialized&&!closed){seekResume?.let{MPVLib.setPropertyBoolean("pause",it)};seekResume=null}}}', 'override fun event(eventId:Int){val owner=generation;if(eventId==21)worker.post{if(initialized&&!closed&&owner==generation){seekResume?.let{MPVLib.setPropertyBoolean("pause",it)};seekResume=null}}}')
edit(base+'player/PlayerSession.kt','catch(t:Throwable){error=t.message?:t.javaClass.simpleName;publish()}', 'catch(t:Throwable){error=t.message?:t.javaClass.simpleName;if(created&&!initialized)shutdownEngine();publish()}')
edit(base+'player/PlayerSession.kt','shutdownEngine();texture?.release();texture=st','shutdownEngine();surface?.release();surface=null;texture?.release();texture=st')
edit(base+'player/PlayerSession.kt','fun open(lease:ReadLease,positionUs:Long=0){safe{','fun open(lease:ReadLease,positionUs:Long=0){worker.post{if(closed){lease.close();return@post};try{')
edit(base+'player/PlayerSession.kt','if(surface!=null)initialize();publish()\n }}','if(surface!=null)initialize();publish()\n }catch(t:Throwable){error=t.message;shutdownEngine();publish()}}}')
edit(base+'player/PlayerSession.kt','loop=null;error=null;resumeUs','loop=null;seekResume=null;error=null;state=PlayerState(generation=generation);resumeUs')
edit(base+'player/PlayerSession.kt','MPVLib.create(context.applicationContext)','MPVLib.create(context.applicationContext);created=true')
edit(base+'player/PlayerSession.kt','"pause" to "yes","volume"', '"pause" to "yes","start" to (resumeUs/1e6).toString(),"volume"')
edit(base+'player/PlayerSession.kt','  if(resumeUs>0)MPVLib.command(arrayOf("seek",(resumeUs/1e6).toString(),"absolute+exact"))\n','')
edit(base+'player/PlayerSession.kt','  if(initialized){\n   MPVLib.removeObserver','  if(created){\n   MPVLib.removeObserver')
edit(base+'player/PlayerSession.kt','MPVLib.destroy();initialized=false;NativeStage','MPVLib.destroy();created=false;initialized=false;seekResume=null;NativeStage')
edit(base+'player/PlayerSession.kt','number("decoder-frame-drop-count")?.toLong(),number("frame-drop-count")?.toLong())','number("decoder-frame-drop-count")?.toLong()?.let{d->number("frame-drop-count")?.toLong()?.let{d+it}},number("frame-drop-count")?.toLong(),initialized&&MPVLib.getPropertyBoolean("paused-for-cache")==true)')
edit('app/src/main/jni/main.cpp','static pthread_t event_thread_id;','static pthread_t event_thread_id;\nstatic bool event_thread_started=false;')
edit('app/src/main/jni/main.cpp','    pthread_setname_np(event_thread_id, "event_thread");','    event_thread_started=true;\n    pthread_setname_np(event_thread_id, "event_thread");')
edit('app/src/main/jni/main.cpp','    pthread_join(event_thread_id, NULL);','    if(event_thread_started){pthread_join(event_thread_id,NULL);event_thread_started=false;}')
edit(base+'export/NativeExporter.kt',' external fun analyze',' external fun prepare()\n external fun analyze')
edit('app/src/main/jni/lumaview/export_bridge.cpp','{stopped=false;fraction=0;try{','{try{')
edit('app/src/main/jni/lumaview/export_bridge.cpp','extern "C" JNIEXPORT void JNICALL Java_org_lumaview_mobile_export_NativeExporter_cancel', 'extern "C" JNIEXPORT void JNICALL Java_org_lumaview_mobile_export_NativeExporter_prepare(JNIEnv*,jobject){stopped=false;fraction=0;}\nextern "C" JNIEXPORT void JNICALL Java_org_lumaview_mobile_export_NativeExporter_cancel')
edit('app/src/main/jni/lumaview/export_bridge.cpp','+av_get_media_type_string(f->streams[i]->codecpar->codec_type)+', '+(av_get_media_type_string(f->streams[i]->codecpar->codec_type)?av_get_media_type_string(f->streams[i]->codecpar->codec_type):"unknown")+')
edit(base+'ui/PlayerActivity.kt','private fun startProgress(title:String){busy=true;', 'private fun startProgress(title:String){NativeExporter.prepare();busy=true;')
edit(base+'ui/PlayerActivity.kt','val o=JSONObject(NativeExporter.analyze','check(!cancel.get()){ "已取消" };val o=JSONObject(NativeExporter.analyze')
edit(base+'ui/PlayerActivity.kt','val json=JSONObject(NativeExporter.write','check(!cancel.get()){ "已取消" };val json=JSONObject(NativeExporter.write')
p=root/'app/src/main/jni/lumaview/frame_counter.h'
p.write_text('''#pragma once
#include <stdint.h>
struct lvm_frame_counter { uint64_t last_id, count; };
static inline uint64_t lvm_count_frame(struct lvm_frame_counter *c, uint64_t id, int valid) {
    if (valid && id && id != c->last_id) { c->last_id=id; c->count++; }
    return c->count;
}
''')
edit('ci/patch_engine.py',"change('video/out/gpu/video.c','#include \"video.h\"'", "write('video/out/gpu/lvm_counter.h',Path('app/src/main/jni/lumaview/frame_counter.h').read_text())\nchange('video/out/gpu/video.c','#include \"video.h\"'")
edit('ci/patch_engine.py','#include "common/lvm_shared.h"\')','#include "common/lvm_shared.h"\\n#include "lvm_counter.h"\')')
edit('ci/patch_engine.py','uint64_t lvm_rendered;','struct lvm_frame_counter lvm_frames;')
edit('ci/patch_engine.py','.rendered_frames=++p->lvm_rendered','.rendered_frames=lvm_count_frame(&p->lvm_frames,frame->frame_id,!p->broken_frame)')
edit('shaders/lvm/mobile.glsl',' if(lvm_reset<0.5&&!cut){',' if(lvm_reset<0.5&&lvm_lock>0.5)ev=previous;\n else if(lvm_reset<0.5&&!cut){')
edit('shaders/lvm/mobile.glsl','vec3 smooth=sum/weight','vec3 denoised=sum/weight')
edit('shaders/lvm/mobile.glsl','mix(c,smooth,','mix(c,denoised,')
edit('shaders/lvm/mobile.glsl','(source.rgb-smooth)','(source.rgb-denoised)')
(root/'app/src/main/assets/lvm/mobile.glsl').write_bytes((root/'shaders/lvm/mobile.glsl').read_bytes())
edit(base+'export/NativeExporter.kt','object NativeExporter {','object NativeExporter {\n init { System.loadLibrary("mpv"); System.loadLibrary("player") }')
edit(base+'Core.kt','fun parseTime(value:String):Long?=try {','private fun positiveAdd(a:Long,b:Long):Long { require(a>=0&&b>=0&&a<=Long.MAX_VALUE-b);return a+b }\nprivate fun positiveMultiply(a:Long,b:Long):Long { require(a>=0&&b>=0&&(b==0L||a<=Long.MAX_VALUE/b));return a*b }\nfun parseTime(value:String):Long?=try {')
edit(base+'Core.kt','Math.addExact','positiveAdd')
edit(base+'Core.kt','Math.multiplyExact','positiveMultiply')
edit('app/src/test/java/org/lumaview/mobile/CoreTest.kt','class CoreTest {','''class CoreTest {
    @Test fun unchangedPresetPreservesComparisonAndLock() {
        val s=EnhanceSettings(bypass=true,locked=true)
        assertEquals(s,s.selectMode(s.mode))
        assertFalse(s.selectMode(2).bypass)
        assertFalse(s.selectMode(2).locked)
    }
    @Test fun preciseTimeRoundTripsMicroseconds() {
        for(v in listOf(0L,1L,1234567L,86400000001L,Long.MAX_VALUE/2))
            assertEquals(v,parseTime(formatTime(v,true)))
    }
''')
# The unused transport draft was never executed and is not part of product source.
(root/'ci/verified-fixes.b64').unlink(missing_ok=True)
files=list((root/'app/src/main/java/org/lumaview/mobile').rglob('*.kt'))+[root/'shaders/lvm/mobile.glsl',root/'ci/patch_engine.py',root/'app/src/main/jni/main.cpp',root/'app/src/main/jni/lumaview/export_bridge.cpp',root/'app/src/main/jni/lumaview/frame_counter.h']
(root/'ci/verified-fixes-applied.json').write_text(json.dumps({p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2))
print('Readable hardening changes applied')
