package org.lumaview.mobile

import java.util.Locale
import kotlin.math.*

data class SourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun crop(): String = "${width}x${height}+${left}+${top}"
}
class RoiMath(val videoW: Int, val videoH: Int, val screenW: Int, val screenH: Int) {
    val scale = min(screenW.toDouble() / videoW.coerceAtLeast(1), screenH.toDouble() / videoH.coerceAtLeast(1))
    val offsetX = (screenW-videoW*scale)/2
    val offsetY = (screenH-videoH*scale)/2
    fun point(x: Double, y: Double): Pair<Double, Double>? {
        if (!x.isFinite() || !y.isFinite() || scale <= 0) return null
        val a = (x-offsetX)/scale; val b = (y-offsetY)/scale
        return if (a in 0.0..videoW.toDouble() && b in 0.0..videoH.toDouble()) Pair(a,b) else null
    }
    fun select(x1: Double, y1: Double, x2: Double, y2: Double, minimum: Double): SourceRect? {
        if (abs(x2-x1)<minimum || abs(y2-y1)<minimum) return null
        val a=point(x1,y1)?:return null;val b=point(x2,y2)?:return null
        val r=SourceRect(floor(min(a.first,b.first)).toInt(),floor(min(a.second,b.second)).toInt(),ceil(max(a.first,b.first)).toInt(),ceil(max(a.second,b.second)).toInt())
        return r.takeIf { it.width >= 16 && it.height >= 16 }
    }
}
data class ClipRange(val startUs: Long, val endUs: Long) {
    fun valid(durationUs: Long?): Boolean = durationUs != null && startUs >= 0 && startUs < endUs && endUs <= durationUs
}
fun playbackLabel(paused: Boolean, speed: Double): String = "${if(paused) "暂停" else "播放"} · ${String.format(Locale.US,"%.2f",speed)}×${if(speed<1) " 慢速" else if(speed>1) " 快速" else ""}"
object ExposureReference {
    fun target(samples: DoubleArray, cap: Double): Double {
        val finite=samples.filter { it.isFinite() && it>=0 }
        if(finite.isEmpty())return 0.0
        val mean=finite.sumOf { ln(max(it,1.0/65536.0))/ln(2.0) }/finite.size
        val bright=finite.count { it>=0.75 }.toDouble()/finite.size
        return (ln(0.18)/ln(2.0)-mean).coerceIn(0.0,cap.coerceIn(0.0,4.0))*(1.0-(bright*1.5).coerceIn(0.0,0.85))
    }
    fun smooth(previous: Double, target: Double, dt: Double): Double {
        if(!dt.isFinite() || dt<=0)return previous
        return previous+((1.0-exp(-dt/0.4))*(target-previous)).coerceIn(-dt,dt)
    }
    fun tone(luma: Double, ev: Double): Double {
        val gain=2.0.pow(ev.coerceIn(0.0,4.0));val y=luma.coerceIn(0.0,1.0)
        return y*gain/(1+y*(gain-1))
    }
}
