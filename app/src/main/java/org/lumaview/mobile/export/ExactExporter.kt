package org.lumaview.mobile.export
import android.content.Context
import android.net.Uri
import android.os.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import org.lumaview.mobile.ClipRange
import java.io.File

/** Only editing uses Transformer; the main player is libmpv. */
class ExactExporter(private val context:Context){
 private val thread=HandlerThread("LumaView-exact-export").apply{start()};private val handler=Handler(thread.looper);private val ui=Handler(Looper.getMainLooper())
 private var transformer:Transformer?=null;private var finished=false;private var callback:((Result<String>)->Unit)?=null
 fun start(uri:Uri,range:ClipRange,temp:File,audio:Boolean,bitrate:Int,progress:(Int)->Unit,reply:(Result<String>)->Unit){handler.post{
  callback=reply;finished=false
  try{
   val default=DefaultEncoderFactory.Builder(context).setEnableFallback(false).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
   val factory=object:Codec.EncoderFactory by default {override fun videoNeedsEncoding()=true;override fun audioNeedsEncoding()=true}
   val item=MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(range.startUs).setEndPositionUs(range.endUs).build()).build()
   val edited=EditedMediaItem.Builder(item).setRemoveAudio(!audio).build()
   transformer=Transformer.Builder(context).setLooper(thread.looper).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(factory).experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false).addListener(object:Transformer.Listener{
    override fun onCompleted(composition:Composition,result:ExportResult){
     if(result.videoEncoderName==null)finish(Result.failure(IllegalStateException("输出未发生预期重编码，不能标为精确模式")))else finish(Result.success("视频编码器：${result.videoEncoderName}\n音频编码器：${result.audioEncoderName?:"无音轨"}\n实际时长：${result.durationMs} ms\n本模式为重编码，不是原码率／原码流。"))
    }
    override fun onError(composition:Composition,result:ExportResult,exception:ExportException){finish(Result.failure(exception))}
   }).build()
   transformer!!.start(edited,temp.absolutePath)
   val poll=object:Runnable{override fun run(){if(finished)return;val holder=ProgressHolder();if(transformer?.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)ui.post{progress(holder.progress)};handler.postDelayed(this,350)}};handler.post(poll)
  }catch(t:Throwable){finish(Result.failure(t))}
 }}
 private fun finish(result:Result<String>){if(finished)return;finished=true;transformer=null;ui.post{callback?.invoke(result);callback=null};handler.removeCallbacksAndMessages(null);thread.quitSafely()}
 fun cancel(){handler.post{transformer?.cancel();finish(Result.failure(IllegalStateException("已取消导出")))}}
}
