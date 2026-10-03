package org.lumaview.mobile

import java.util.Locale
import kotlin.math.*

/** Source pixel edges, not screenshot pixels. All rectangles are half-open. */
data class RoiRect(val left:Double,val top:Double,val right:Double,val bottom:Double) {
 val width get()=right-left; val height get()=bottom-top
 fun valid()=listOf(left,top,right,bottom).all { it.isFinite() } && width>=16 && height>=16
 fun crop():String {
  val l=(floor(left/2)*2).toInt().coerceAtLeast(0);val t=(floor(top/2)*2).toInt().coerceAtLeast(0)
  val r=(ceil(right/2)*2).toInt();val b=(ceil(bottom/2)*2).toInt()
  return "${r-l}x${b-t}+$l+$t"
 }
 fun clamped(w:Int,h:Int):RoiRect? {val v=RoiRect(left.coerceIn(0.0,w.toDouble()),top.coerceIn(0.0,h.toDouble()),right.coerceIn(0.0,w.toDouble()),bottom.coerceIn(0.0,h.toDouble()));return v.takeIf{it.valid()}}
 fun zoomed(factor:Double,fx:Double=.5,fy:Double=.5):RoiRect {
  val f=factor.coerceIn(.125,8.0);val w=width/f;val h=height/f
  val x=left+width*fx;val y=top+height*fy
  return RoiRect(x-w*fx,y-h*fy,x+w*(1-fx),y+h*(1-fy))
 }
 fun translateClamped(dx:Double,dy:Double,bounds:RoiRect):RoiRect {
  val w=min(width,bounds.width);val h=min(height,bounds.height)
  val x=(left+dx).coerceIn(bounds.left,bounds.right-w);val y=(top+dy).coerceIn(bounds.top,bounds.bottom-h)
  return RoiRect(x,y,x+w,y+h)
 }
}

class RoiMath(val sw:Int,val sh:Int,val vw:Int,val vh:Int,val rotation:Int=0,val sar:Double=1.0,val roi:RoiRect=RoiRect(0.0,0.0,sw.toDouble(),sh.toDouble())) {
 private val rot=((rotation%360)+360)%360
 private val rw=roi.width*sar;private val rh=roi.height
 private val dw=if(rot%180==90)rh else rw;private val dh=if(rot%180==90)rw else rh
 val scale=if(dw>0&&dh>0)min(vw/dw,vh/dh)else 0.0
 val displayWidth=dw*scale;val displayHeight=dh*scale
 val left=(vw-displayWidth)/2;val top=(vh-displayHeight)/2
 fun point(x:Double,y:Double):Pair<Double,Double>? {
  if(scale<=0||rot%90!=0||!x.isFinite()||!y.isFinite()||x<left||x>left+displayWidth||y<top||y>top+displayHeight)return null
  val u=(x-left)/displayWidth;val v=(y-top)/displayHeight
  val q=when(rot){90->v to 1-u;180->1-u to 1-v;270->1-v to u;else->u to v}
  return roi.left+q.first*roi.width to roi.top+q.second*roi.height
 }
 fun screen(x:Double,y:Double):Pair<Double,Double> {
  val u=(x-roi.left)/roi.width;val v=(y-roi.top)/roi.height
  val q=when(rot){90->1-v to u;180->1-u to 1-v;270->v to 1-u;else->u to v}
  return left+q.first*displayWidth to top+q.second*displayHeight
 }
 fun select(a:Double,b:Double,c:Double,d:Double,min:Double):RoiRect? {
  if(abs(a-c)<min||abs(b-d)<min)return null
  val p=point(a,b)?:return null;val q=point(c.coerceIn(left,left+displayWidth),d.coerceIn(top,top+displayHeight))?:return null
  return RoiRect(min(p.first,q.first),min(p.second,q.second),max(p.first,q.first),max(p.second,q.second)).clamped(sw,sh)
 }
}
data class ClipRange(val startUs:Long,val endUs:Long){ fun valid(duration:Long?)=duration!=null&&startUs>=0&&endUs>startUs&&endUs<=duration }
fun playbackLabel(paused:Boolean,speed:Double)=String.format(Locale.ROOT,"%s · %.2f×%s",if(paused)"暂停" else "播放",speed,if(speed<1)" 慢速" else if(speed>1)" 快速" else "")
fun formatTime(us:Long?,precise:Boolean=false):String {
 if(us==null||us<0)return "--:--"
 val s=us/1_000_000;val h=s/3600;val m=s/60%60;val sec=s%60
 val b=if(h>0)String.format(Locale.ROOT,"%d:%02d:%02d",h,m,sec)else String.format(Locale.ROOT,"%02d:%02d",m,sec)
 return if(precise)b+String.format(Locale.ROOT,".%06d",us%1000000)else b
}
private fun positiveAdd(a:Long,b:Long):Long { require(a>=0&&b>=0&&a<=Long.MAX_VALUE-b);return a+b }
private fun positiveMultiply(a:Long,b:Long):Long { require(a>=0&&b>=0&&(b==0L||a<=Long.MAX_VALUE/b));return a*b }
fun parseTime(value:String):Long?=try {
 val p=value.trim().split(':');if(p.size !in 1..3)null else {
  val tail=p.last().split('.');require(tail.size<=2&&tail[0].all{it.isDigit()}&&tail[0].isNotEmpty())
  val seconds=tail[0].toLong();require(p.size==1||seconds<60)
  var total=seconds
  if(p.size>=2){val minutes=p[p.size-2].toLong();require(minutes>=0&&(p.size<3||minutes<60));total=positiveAdd(total,positiveMultiply(minutes,60))}
  if(p.size==3){val hours=p[0].toLong();require(hours>=0);total=positiveAdd(total,positiveMultiply(hours,3600))}
  val frac=if(tail.size==2){require(tail[1].length in 1..6&&tail[1].all{it.isDigit()});tail[1].padEnd(6,'0').toLong()}else 0
  positiveAdd(positiveMultiply(total,1_000_000),frac)
 }
}catch(_:Exception){null}
object ExposureReference {
 fun target(v:DoubleArray,cap:Double):Double {
  val a=v.filter{it.isFinite()&&it>=0};if(a.isEmpty())return 0.0
  val mean=a.sumOf{log2(max(it,1.0/65536))}/a.size
  val bright=a.count{it>=.75}.toDouble()/a.size
  return (log2(.18)-mean).coerceIn(0.0,cap.coerceIn(0.0,4.0))*(1.0-(bright*1.5).coerceIn(0.0,.85))
 }
 fun smooth(a:Double,b:Double,dt:Double):Double {val d=dt.coerceIn(0.0,1.0);return a+((b-a)*(1-exp(-d/.4))).coerceIn(-d,d)}
 fun tone(v:Double,ev:Double):Double {val x=v.coerceIn(0.0,1.0);val g=2.0.pow(ev.coerceIn(0.0,4.0));return x*g/(1+x*(g-1))}
}
data class EnhanceSettings(val mode:Int=1,val manualEv:Float=0f,val shadows:Float=60f,val contrast:Float=0f,val saturation:Float=100f,val denoise:Float=40f,val detail:Float=8f,val bypass:Boolean=false,val locked:Boolean=false) {
 fun selectMode(value:Int)=if(value==mode)this else copy(mode=value.coerceIn(0,4),bypass=false,locked=false)
 fun values()=floatArrayOf(mode.toFloat(),manualEv.coerceIn(-1f,1f),shadows.coerceIn(0f,100f),contrast.coerceIn(-25f,25f),saturation.coerceIn(0f,150f),denoise.coerceIn(0f,100f),detail.coerceIn(0f,30f),if(bypass)1f else 0f,if(locked)1f else 0f)
}
/** Only valid playback windows count. Unknown counters are not zero drops. */
class QualityGovernor {
 var level=0;private set
 private var start=0L;private var lastChange=Long.MIN_VALUE/2;private var frames=0L;private var drops=0L;private var bad=0;private var stable=0L
 fun reset(now:Long){start=now;frames=0;drops=0;bad=0;stable=now}
 fun update(now:Long,frameDelta:Long?,dropDelta:Long?,valid:Boolean,thermal:Int?):Int {
  if(thermal!=null&&thermal>=3&&now-lastChange>=10_000){level=(level+1).coerceAtMost(4);lastChange=now;reset(now);return level}
  if(!valid||frameDelta==null||dropDelta==null||frameDelta<0||dropDelta<0){reset(now);return level}
  if(start==0L)start=now
  frames+=frameDelta;drops+=dropDelta
  if(now-start<5000)return level
  val poor=frames>0&&drops.toDouble()/(frames+drops)>.01
  bad=if(poor)bad+1 else 0
  if(poor)stable=now
  if(bad>=2&&now-lastChange>=10_000){level=(level+1).coerceAtMost(4);lastChange=now;bad=0;stable=now}
  else if(!poor&&level>0&&now-stable>=30_000&&now-lastChange>=10_000){level--;lastChange=now;stable=now}
  start=now;frames=0;drops=0;return level
 }
}
