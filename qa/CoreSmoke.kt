package org.lumaview.mobile
fun main() {
 val m=RoiMath(1920,1080,1000,1000)
 check(m.point(100.0,50.0)==null)
 check(kotlin.math.abs(m.point(500.0,500.0)!!.first-960)<1e-6)
 check(m.select(750.0,600.0,250.0,400.0,24.0)!!.crop()=="960x384+480+348")
 check(!ClipRange(5,3).valid(6));check(!ClipRange(0,3).valid(null))
 check(ClipRange(0,3).valid(3))
 check(playbackLabel(true,.25)=="暂停 · 0.25× 慢速")
 check(ExposureReference.target(DoubleArray(256){.01},4.0)>3.9)
 check(ExposureReference.target(DoubleArray(256){.8},4.0)==0.0)
 check(ExposureReference.target(DoubleArray(256),4.0).isFinite())
 var p=-1.0
 repeat(1001){val v=ExposureReference.tone(it/1000.0,4.0);check(v.isFinite()&&v>=p&&v<=1);p=v}
 println("CORE_BEHAVIOR_PASS")
}
