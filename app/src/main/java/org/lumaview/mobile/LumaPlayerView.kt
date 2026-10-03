package org.lumaview.mobile

import android.content.Context
import android.util.AttributeSet
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import java.io.File

class LumaPlayerView(context:Context,attrs:AttributeSet):BaseMPVView(context,attrs) {
    override fun initOptions() {
        val shader=File(context.filesDir,"lumaview.glsl")
        context.assets.open("lumaview.glsl").use { src -> shader.outputStream().use { src.copyTo(it) } }
        for((key,value) in listOf("config" to "no","vo" to "gpu","gpu-context" to "android","gpu-dumb-mode" to "no","opengl-es" to "yes","hwdec" to "auto-safe","ao" to "audiotrack,opensles","keep-open" to "yes","idle" to "yes","save-position-on-quit" to "no","glsl-shaders" to shader.absolutePath,"lvm-params" to "1 0 0.5 0 1 0.3 0 1 0 0","demuxer-max-bytes" to "64MiB","osd-level" to "0")) {
            val code=MPVLib.setOptionString(key,value)
            check(code>=0) { "播放器选项 $key 未生效（$code）" }
        }
    }
    override fun postInitOptions() {}
    override fun observeProperties() {}
}
