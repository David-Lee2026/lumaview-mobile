package org.lumaview.mobile.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.*
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import org.lumaview.mobile.*
import org.lumaview.mobile.storage.ReadLease
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class Track(val id:Int,val type:String,val title:String,val selected:Boolean,val ffIndex:Int?)
data class PlayerState(val generation:Long=0,val positionUs:Long?=null,val durationUs:Long?=null,val paused:Boolean=true,val speed:Double=1.0,val width:Int=0,val height:Int=0,val rotation:Int=0,val sar:Double=1.0,val seekable:Boolean=false,val decoder:String="尚未打开",val hdr:Boolean=false,val tracks:List<Track> = emptyList(),val receipt:DoubleArray?=null,val error:String?=null,val dropped:Long?=null,val outputFrames:Long?=null,val sourceRect:RoiRect?=null,val rendererTier:Int=0,val decoderMode:String="no",val videoNotice:String?=null)
/** All native calls, including teardown, are serialized on one process-wide worker. */
class PlayerSession(private val context:Context,private val callback:(PlayerState)->Unit) {
 companion object {private val thread=HandlerThread("LumaView-player").apply{start()};private val worker=Handler(thread.looper);private val serial=AtomicLong()}
 private val ui=Handler(Looper.getMainLooper());private var initialized=false;private var created=false;@Volatile private var closed=false
 private var input:ReadLease?=null;private var surface:Surface?=null;private var texture:SurfaceTexture?=null
 private var surfaceWidth=1;private var surfaceHeight=1;private var engineEpoch=0L
 private var rendererTier=0;private var videoNotice:String?=null
 private var decoderMode=context.getSharedPreferences("playback",0).getString("decoder","no").takeIf{it=="no"||it=="mediacodec-copy"}?:"no"
 private var generation=0L;private var surfaceGeneration=0L;private var revision=0L;private var request=0L
 private var settings=EnhanceSettings();private var crop:RoiRect?=null;private var rotation=0
 private var error:String?=null;private var resumeUs=0L;private var resumePaused=false;private var resumeSpeed=1.0
 @Volatile var state=PlayerState();private set
 private var loop:ClipRange?=null;private var seekResume:Boolean?=null;private var lastTrackRead=0L;private var trackCache=emptyList<Track>()
 private val logLines=ArrayDeque<String>()
 private val logObserver=object:MPVLib.LogObserver {override fun logMessage(prefix:String,level:Int,text:String){
  if(level<=30||text.contains("GL_VERSION")||text.contains("GL_RENDERER")||text.contains("GL_VENDOR")||text.contains("Using hardware decoding")){synchronized(logLines){if(logLines.size>=100)logLines.removeFirst();logLines.add("$prefix: ${text.take(320)}")}}
 }}
 private val observer=object:MPVLib.EventObserver {
  override fun eventProperty(property:String){};override fun eventProperty(property:String,value:Long){};override fun eventProperty(property:String,value:Boolean){};override fun eventProperty(property:String,value:String){};override fun eventProperty(property:String,value:Double){}
  override fun event(eventId:Int){
   if(eventId==8||eventId==21){val epoch=engineEpoch;worker.post{if(initialized&&!closed&&epoch==engineEpoch){
    if(eventId==8){if(resumeUs>0){MPVLib.command(arrayOf("seek",(resumeUs/1e6).toString(),"absolute+exact"));resumeUs=0};MPVLib.setPropertyBoolean("pause",resumePaused)}
    else {seekResume?.let{MPVLib.setPropertyBoolean("pause",it)};seekResume=null}
   }}}
  }
 }
 private fun safe(onClosed:()->Unit={},block:()->Unit){worker.post{if(!closed)try{block()}catch(t:Throwable){error=t.message?:t.javaClass.simpleName;publish()}else onClosed()}}
 fun attach(st:SurfaceTexture,w:Int,h:Int){
  safe {
   if(texture!==st){shutdownEngine();texture?.release();texture=st;surfaceGeneration=serial.incrementAndGet();surface=Surface(st)}
   surfaceWidth=w.coerceAtLeast(1);surfaceHeight=h.coerceAtLeast(1)
   st.setDefaultBufferSize(surfaceWidth,surfaceHeight)
   if(input!=null&&!initialized)initialize()
   if(initialized)MPVLib.setPropertyString("android-surface-size","${w}x$h")
  }
 }
 /** TextureView returns false from onSurfaceTextureDestroyed; we own its final release. */
 fun detach(st:SurfaceTexture){worker.post{
  if(texture===st){resumeUs=state.positionUs?:resumeUs;resumePaused=true;shutdownEngine();surface?.release();surface=null;texture=null;st.release()}
  else st.release()
 }}
 fun open(lease:ReadLease,positionUs:Long=0){safe({lease.close()}){
  shutdownEngine();input?.close();input=lease;generation=serial.incrementAndGet();revision++;crop=null;rotation=0;loop=null;error=null;rendererTier=0;videoNotice=null;resumeUs=positionUs;resumePaused=false;resumeSpeed=1.0;trackCache=emptyList();state=PlayerState(generation)
  if(surface!=null)initialize();publish()
 }}
 private fun initialize(){
  val lease=input?:return;val target=surface?:return
  for(name in listOf("mobile","compatible")){val shader=File(context.filesDir,"$name.glsl");context.assets.open("lvm/$name.glsl").use{src->shader.outputStream().use{src.copyTo(it)}}}
  val shader=File(context.filesDir,if(rendererTier==1)"compatible.glsl" else "mobile.glsl")
  engineEpoch=serial.incrementAndGet();MPVLib.create(context.applicationContext);created=true
  try {
  val options=linkedMapOf("config" to "no","load-scripts" to "no","autoload-files" to "no","ytdl" to "no","load-auto-profiles" to "no","osc" to "no","input-default-bindings" to "no","osd-level" to "0","vo" to "gpu","gpu-context" to "android","opengl-es" to "yes","hwdec" to decoderMode,"fbo-format" to "rgba8","android-surface-size" to "${surfaceWidth}x$surfaceHeight","ao" to "audiotrack,opensles","video-sync" to "audio","scale" to "bilinear","dscale" to "bilinear","cscale" to "bilinear","interpolation" to "no","deband" to "no","gpu-shader-cache-dir" to File(context.cacheDir,"shader-cache").absolutePath,"demuxer-max-bytes" to "33554432","demuxer-max-back-bytes" to "16777216","keep-open" to "yes","idle" to "yes","force-window" to "yes","pause" to "yes","volume" to "100","sub-auto" to "no","audio-file-auto" to "no","glsl-shaders" to if(rendererTier<2)shader.absolutePath else "")
  options.forEach{(k,v)->val rc=MPVLib.setOptionString(k,v);check(rc>=0){"内核选项不可用：$k ($rc)"}}
  MPVLib.addObserver(observer);MPVLib.addLogObserver(logObserver)
  // Set wid before mpv_initialize can start a force-window VO.
  MPVLib.attachSurface(target);MPVLib.init();initialized=true
  // fd:// is a read-only lease kept alive for the entire media session.
  MPVLib.command(arrayOf("loadfile","fd://${lease.fd}","replace"))
  MPVLib.setPropertyDouble("speed",resumeSpeed);MPVLib.setPropertyInt("video-rotate",rotation)
  crop?.let{MPVLib.setPropertyString("video-crop",it.crop())}
  // Resume seek is performed on FILE_LOADED, after the demuxer is ready.
  MPVLib.setPropertyBoolean("pause",resumePaused)
  submit(true);worker.removeCallbacks(tick);worker.postDelayed(tick,200)
  }catch(t:Throwable){shutdownEngine();throw t}
 }
 private fun shutdownEngine(){
  worker.removeCallbacks(tick)
  if(created){
   MPVLib.removeObserver(observer);MPVLib.removeLogObserver(logObserver)
   // This is the real termination barrier. No sleep or optimistic property assignment.
   MPVLib.destroy();initialized=false;created=false;NativeStage.releaseSurfaceAfterShutdown();subtitleLeases.forEach{it.close()};subtitleLeases.clear()
  }
 }
 fun close(){closed=true;worker.post{shutdownEngine();surface?.release();surface=null;texture?.release();texture=null;input?.close();input=null}}
 /** Duplicate the authorized current input; cached providers do not need a second full copy. */
 fun readLease(reply:(Result<ReadLease>)->Unit){worker.post{
  val result=runCatching{check(!closed);val source=checkNotNull(input){"媒体未打开"};ReadLease(source.uri,source.name,ParcelFileDescriptor.dup(source.descriptor.fileDescriptor),source.bytes)}
  ui.post{if(closed){result.getOrNull()?.close();reply(Result.failure(IllegalStateException("播放器已关闭")))}else reply(result)}
 }}
 fun pause(value:Boolean){safe{resumePaused=value;if(initialized)MPVLib.setPropertyBoolean("pause",value)}}
 fun speed(value:Double){safe{resumeSpeed=value.coerceIn(.25,2.0);if(initialized)MPVLib.setPropertyDouble("speed",resumeSpeed)}}
 fun seek(us:Long,final:Boolean=true,restorePaused:Boolean?=null){safe{
  if(initialized&&state.seekable){val t=us.coerceIn(0,state.durationUs?:Long.MAX_VALUE);seekResume=restorePaused;MPVLib.command(arrayOf("seek",(t/1e6).toString(),if(final)"absolute+exact" else "absolute+keyframes"));if(final)submit(true)}
 }}
 fun setLoop(range:ClipRange?){safe{loop=range;if(initialized){MPVLib.setPropertyString("ab-loop-a",range?.let{(it.startUs/1e6).toString()}?:"no");MPVLib.setPropertyString("ab-loop-b",range?.let{(it.endUs/1e6).toString()}?:"no")}}}
 fun viewport(value:RoiRect?,rotate:Int=rotation){safe{
  crop=value;rotation=((rotate%360)+360)%360;revision++;settings=settings.copy(locked=false)
  if(initialized){MPVLib.setPropertyInt("video-rotate",rotation);MPVLib.setPropertyString("video-crop",crop?.crop()?:"");submit(true)}
 }}
 fun enhance(value:EnhanceSettings,reset:Boolean=false){safe{
  // Explicit exposure/mode changes must redraw even when the media PTS is
  // unchanged. Temporal adaptation has dt=0 on a paused frame.
  val resetExposure=reset||value.manualEv!=settings.manualEv||value.mode!=settings.mode||value.bypass!=settings.bypass||(settings.locked&&!value.locked)
  settings=value;if(initialized)submit(resetExposure)
 }}
 private fun submit(reset:Boolean){request++;val effective=if(rendererTier>=2)settings.copy(bypass=true)else settings;val rc=NativeStage.submit(effective.values(),generation,surfaceGeneration,revision,request,reset);if(rc!=0)error="增强参数提交失败 ($rc)"}
 /** Reconfigure decoding without abandoning the Surface or resetting the timeline. */
 fun decoder(value:String){safe{require(value=="no"||value=="mediacodec-copy");decoderMode=value;context.getSharedPreferences("playback",0).edit().putString("decoder",value).apply();if(initialized){MPVLib.setPropertyString("hwdec",value);submit(true)}}}
 private fun recoverRenderer(){
  if(rendererTier>=2)return
  rendererTier++
  videoNotice=if(rendererTier==1)"兼容增强：降噪／细节已停用" else "增强已停用，正在重建原画"
  MPVLib.setPropertyString("glsl-shaders",if(rendererTier==1)File(context.filesDir,"compatible.glsl").absolutePath else "")
  submit(true)
 }
 fun frame(forward:Boolean){safe{if(initialized){MPVLib.setPropertyBoolean("pause",true);MPVLib.command(arrayOf(if(forward)"frame-step" else "frame-back-step"))}}}
 fun selectTrack(type:String,id:Int?){safe{if(initialized)MPVLib.setPropertyString(if(type=="audio")"aid" else "sid",id?.toString()?:"no")}}
 fun externalSubtitle(lease:ReadLease){safe({lease.close()}){if(initialized){ // mpv opens its own file descriptor; retain lease until session end.
  subtitleLeases.add(lease);MPVLib.command(arrayOf("sub-add","fd://${lease.fd}","select",lease.name))
 }else lease.close()}}
 private val subtitleLeases=ArrayList<ReadLease>()
 fun screenshot(file:File,reply:(Result<File>)->Unit){safe{val r=runCatching{check(initialized);MPVLib.command(arrayOf("screenshot-to-file",file.absolutePath,"window"));check(file.exists()&&file.length()>0){"当前画面无法截图"};file};ui.post{reply(r)}}}
 fun diagnostic(reply:(String)->Unit){safe{val text="LumaView Mobile 0.1.1-test\n解码设置：$decoderMode\n增强路径：$rendererTier\n$videoNotice\nAPI ${Build.VERSION.SDK_INT}\nABI ${Build.SUPPORTED_ABIS.joinToString()}\n${state.decoder}\nSize ${state.width}×${state.height}\nHDR ${state.hdr}\nMode receipt ${state.receipt?.joinToString()}\n最近内核日志：\n${synchronized(logLines){logLines.joinToString("\n")}}\n";ui.post{reply(text)}}}
 /** Free hardware decoder before a Transformer job, and reopen the same media afterward. */
 fun suspendForExport(done:()->Unit){safe{resumeUs=state.positionUs?:0;resumePaused=true;resumeSpeed=state.speed;shutdownEngine();ui.post(done)}}
 fun resumeAfterExport(){safe{if(!initialized&&input!=null&&surface!=null)initialize()}}
 private val tick=object:Runnable {override fun run(){if(!initialized||closed)return;try{publish()}catch(t:Throwable){error=t.message};worker.postDelayed(this,250)}}
 private fun publish(){
  fun number(k:String)=if(initialized)MPVLib.getPropertyDouble(k)?.takeIf{it.isFinite()}else null
  fun text(k:String)=if(initialized)MPVLib.getPropertyString(k)else null
  if(initialized&&SystemClock.elapsedRealtime()-lastTrackRead>1500){lastTrackRead=SystemClock.elapsedRealtime();val count=MPVLib.getPropertyInt("track-list/count")?:0
   trackCache=(0 until count.coerceIn(0,128)).map{n->val b="track-list/$n";Track(MPVLib.getPropertyInt("$b/id")?:n,text("$b/type")?:"unknown",listOfNotNull(text("$b/lang"),text("$b/title"),text("$b/codec")).joinToString(" · "),MPVLib.getPropertyBoolean("$b/selected")==true,MPVLib.getPropertyInt("$b/ff-index"))}
  }
  val w=number("video-params/w")?.toInt()?:state.width;val h=number("video-params/h")?.toInt()?:state.height
  val par=number("video-params/par")?.takeIf{it>0}?:1.0
  val cx=number("video-params/crop-x")?:0.0;val cy=number("video-params/crop-y")?:0.0
  val cw=number("video-params/crop-w")?:w.toDouble();val ch=number("video-params/crop-h")?:h.toDouble()
  val sourceRect=RoiRect(cx,cy,cx+cw,cy+ch).clamped(w,h)
  val trc=text("video-params/gamma")?:"";var receipt=if(initialized)NativeStage.receipt()?.takeIf{it.size>=14&&it[0]==generation.toDouble()&&it[1]==surfaceGeneration.toDouble()&&it[2]==revision.toDouble()&&it[3]==request.toDouble()}else null
  if(receipt?.get(6)==2.0&&rendererTier<2){recoverRenderer();receipt=null}
  val s=PlayerState(generation,number("time-pos")?.times(1e6)?.toLong(),number("duration")?.times(1e6)?.toLong(),if(initialized)MPVLib.getPropertyBoolean("pause")?:true else true,number("speed")?:resumeSpeed,w,h,((number("video-params/rotate")?.toInt()?:0)%360+360)%360,par,initialized&&MPVLib.getPropertyBoolean("seekable")==true,"${text("video-codec")?:"等待解码"} / ${text("hwdec-current")?:"未知"}",trc in setOf("pq","hlg","st2084")||receipt?.get(6)==1.0,trackCache,receipt,error,number("decoder-frame-drop-count")?.toLong(),number("frame-drop-count")?.toLong(),sourceRect,rendererTier,decoderMode,videoNotice)
  state=s;ui.post{if(!closed&&s.generation==generation)callback(s)}
 }
}
