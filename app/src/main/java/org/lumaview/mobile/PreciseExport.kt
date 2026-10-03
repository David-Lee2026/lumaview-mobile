package org.lumaview.mobile

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import android.media.metrics.LogSessionId
import java.io.File

@UnstableApi
class PreciseExport(private val context:Context) {
    private var active:Transformer?=null
    private var activeOutput:File?=null
    fun start(input:File,output:File,range:ClipRange,audio:Boolean,done:(String?,String?)->Unit) {
        check(active==null)
        activeOutput=output
        val factory=DefaultEncoderFactory.Builder(context).setEnableFallback(false).build()
        val force=object:Codec.EncoderFactory by factory {
            override fun videoNeedsEncoding()=true
            override fun audioNeedsEncoding()=true
        }
        val item=MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startUs/1000).setEndPositionMs(range.endUs/1000).setStartsAtKeyFrame(false).build()).build()
        val edited=EditedMediaItem.Builder(item).setRemoveAudio(!audio).build()
        active=Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(force)
            .experimentalSetTrimOptimizationEnabled(false).experimentalSetMp4EditListTrimEnabled(false)
            .addListener(object:Transformer.Listener {
                override fun onCompleted(composition:Composition,r:ExportResult) {
                    active=null;activeOutput=null
                    done("{\"videoEncoder\":\"${r.videoEncoderName}\",\"audioEncoder\":\"${r.audioEncoderName}\",\"videoFrames\":${r.videoFrameCount},\"durationMs\":${r.approximateDurationMs},\"width\":${r.width},\"height\":${r.height},\"videoConversion\":${r.videoConversionProcess},\"audioConversion\":${r.audioConversionProcess}}",null)
                }
                override fun onError(composition:Composition,r:ExportResult,e:ExportException){active=null;activeOutput=null;output.delete();done(null,e.message?:"精确导出失败")}
            }).build()
        active!!.start(edited,output.absolutePath)
    }
    fun cancel(){active?.cancel();active=null;activeOutput?.delete();activeOutput=null}
}
