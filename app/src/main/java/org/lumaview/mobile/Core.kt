package org.lumaview.mobile

data class SourceRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun crop(): String = ""
}
class RoiMath(val videoW: Int, val videoH: Int, val screenW: Int, val screenH: Int) {
    fun point(x: Double, y: Double): Pair<Double, Double>? = null
    fun select(x1: Double, y1: Double, x2: Double, y2: Double, minimum: Double): SourceRect? = null
}
data class ClipRange(val startUs: Long, val endUs: Long) {
    fun valid(durationUs: Long?): Boolean = false
}
fun playbackLabel(paused: Boolean, speed: Double): String = ""
object ExposureReference {
    fun target(samples: DoubleArray, cap: Double): Double = 0.0
    fun smooth(previous: Double, target: Double, dt: Double): Double = 0.0
    fun tone(luma: Double, ev: Double): Double = 0.0
}
