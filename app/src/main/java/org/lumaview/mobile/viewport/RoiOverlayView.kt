package org.lumaview.mobile.viewport
import android.content.Context
import android.graphics.*
import android.view.*
import org.lumaview.mobile.*
import kotlin.math.*

/** One touch sequence owns either selection, zoom/pan or playback gestures. */
class RoiOverlayView(context:Context):View(context) {
 var math:RoiMath?=null
 var current:RoiRect?=null
 var selection:RoiRect?=null;private set
 var selecting=false;private set
 var locked=false
 var onViewport:(RoiRect?)->Unit={}
 var onTap:()->Unit={}
 var onDouble:(Boolean)->Unit={}
 var onVolume:(Float)->Unit={}
 var onBrightness:(Float)->Unit={}
 var onSeek:(Float,Boolean)->Unit={_,_->}
 var onSelection:(Boolean)->Unit={}
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private val dp=resources.displayMetrics.density
 private var startX=0f;private var startY=0f;private var prevX=0f;private var prevY=0f
 private var owner="";private var activeId=-1;private var base:RoiRect?=null;private var lastUp=0L;private var changed=false;private var corner=-1
 private var startSource:Pair<Double,Double>?=null
 private val pinch=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
  override fun onScaleBegin(d:ScaleGestureDetector):Boolean= !selecting&&!locked
  override fun onScale(d:ScaleGestureDetector):Boolean {
   val m=math?:return false;val bounds=RoiRect(0.0,0.0,m.sw.toDouble(),m.sh.toDouble());val r=current?:bounds
   val focus=m.point(d.focusX.toDouble(),d.focusY.toDouble())?:return true
   val relative=(bounds.width/r.width*d.scaleFactor).coerceIn(1.0,8.0)
   val factor=relative/(bounds.width/r.width)
   val next=r.zoomed(factor,((focus.first-r.left)/r.width).coerceIn(0.0,1.0),((focus.second-r.top)/r.height).coerceIn(0.0,1.0)).translateClamped(0.0,0.0,bounds)
   current=if(relative<=1.001)null else next;onViewport(current);owner="pinch";changed=true;return true
  }
 })
 fun begin(){if(locked)return;selecting=true;selection=null;owner="";activeId=-1;onSelection(false);invalidate()}
 fun cancel(){selecting=false;selection=null;owner="";activeId=-1;invalidate()}
 fun apply():RoiRect? {val r=selection?.takeIf{it.valid()}?:return null;selecting=false;current=r;selection=null;invalidate();return r}
 override fun onDraw(c:Canvas){super.onDraw(c)
  if(!selecting)return
  val m=math?:return
  val r=selection
  paint.style=Paint.Style.FILL;paint.color=0x66000000;c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
  if(r!=null){val points=listOf(m.screen(r.left,r.top),m.screen(r.right,r.top),m.screen(r.right,r.bottom),m.screen(r.left,r.bottom));val l=points.minOf{it.first}.toFloat();val t=points.minOf{it.second}.toFloat();val rr=points.maxOf{it.first}.toFloat();val b=points.maxOf{it.second}.toFloat()
   paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f*dp;paint.color=0xff48d6cb.toInt();c.drawRect(l,t,rr,b,paint)
   paint.style=Paint.Style.FILL;for(p in points)c.drawCircle(p.first.toFloat(),p.second.toFloat(),7*dp,paint)
   paint.color=Color.WHITE;paint.textSize=14*dp;c.drawText("${r.width.toInt()} × ${r.height.toInt()} 源像素",l.coerceAtLeast(8*dp),(t-12*dp).coerceAtLeast(24*dp),paint)
  }
  paint.color=Color.WHITE;paint.textSize=15*dp;paint.style=Paint.Style.FILL
  c.drawText(if(r==null)"在视频内拖出选框" else "拖动四角调整 · 点应用后放大",16*dp,32*dp,paint)
 }
 override fun onTouchEvent(e:MotionEvent):Boolean {
  if(locked){if(e.actionMasked==MotionEvent.ACTION_UP)onTap();return true}
  val m=math?:return true
  if(!selecting)pinch.onTouchEvent(e)
  if(e.actionMasked==MotionEvent.ACTION_CANCEL){owner="";activeId=-1;return true}
  if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){owner="pinch";return true}
  if(e.actionMasked==MotionEvent.ACTION_POINTER_UP){activeId=-1;owner="pinch";return true}
  when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{
    parent.requestDisallowInterceptTouchEvent(true);activeId=e.getPointerId(0);startX=e.x;startY=e.y;prevX=e.x;prevY=e.y;changed=false;base=if(selecting)selection else current;corner=-1;startSource=m.point(e.x.toDouble(),e.y.toDouble())
    if(selecting){
     val r=selection
     if(r!=null){val pts=listOf(m.screen(r.left,r.top),m.screen(r.right,r.top),m.screen(r.right,r.bottom),m.screen(r.left,r.bottom));corner=pts.indexOfFirst{hypot(it.first-e.x,it.second-e.y)<24*dp};val q=startSource;owner=if(corner>=0)"corner" else if(q!=null&&q.first in r.left..r.right&&q.second in r.top..r.bottom)"move" else "draw"}
     else owner="draw"
     if(owner=="draw")selection=null
    }else owner=if(current!=null)"pan" else "normal"
   }
   MotionEvent.ACTION_MOVE->{
    if(e.pointerCount>1||pinch.isInProgress||activeId<0||owner=="pinch")return true
    val index=e.findPointerIndex(activeId);if(index<0){owner="";return true};val x=e.getX(index);val y=e.getY(index)
    if(hypot((x-startX).toDouble(),(y-startY).toDouble())>6*dp)changed=true
    if(selecting){
     if(owner=="draw")selection=m.select(startX.toDouble(),startY.toDouble(),x.toDouble(),y.toDouble(),24.0*dp)
     else {val r=base;val a=startSource;val p=m.point(x.toDouble().coerceIn(m.left,m.left+m.displayWidth),y.toDouble().coerceIn(m.top,m.top+m.displayHeight))
      if(r!=null&&a!=null&&p!=null){
       val b=RoiRect(0.0,0.0,m.sw.toDouble(),m.sh.toDouble())
       selection=if(owner=="move")r.translateClamped(p.first-a.first,p.second-a.second,b)else {
        val q=when(corner){0->RoiRect(p.first,p.second,r.right,r.bottom);1->RoiRect(r.left,p.second,p.first,r.bottom);2->RoiRect(r.left,r.top,p.first,p.second);else->RoiRect(p.first,r.top,r.right,p.second)}
        q.clamped(m.sw,m.sh)?:selection
       }
      }
     };onSelection(selection!=null);invalidate()
    }else if(owner=="pan"){
     val a=m.point(prevX.toDouble(),prevY.toDouble());val b=m.point(x.toDouble(),y.toDouble());val r=current
     if(a!=null&&b!=null&&r!=null){current=r.translateClamped(a.first-b.first,a.second-b.second,RoiRect(0.0,0.0,m.sw.toDouble(),m.sh.toDouble()));onViewport(current)}
    }else {
     if(owner=="normal"&&changed)owner=if(abs(x-startX)>abs(y-startY))"seek" else if(startX<width/2)"brightness" else "volume"
     when(owner){"seek"->onSeek((x-startX)/width,false);"volume"->onVolume((prevY-y)/height);"brightness"->onBrightness((prevY-y)/height)}
    };prevX=x;prevY=y
   }
   MotionEvent.ACTION_UP->{
    if(owner=="seek")onSeek((e.x-startX)/width,true)
    if(!selecting&&!changed&&owner!="pinch"){
     if(e.eventTime-lastUp<300){onDouble(e.x<width/2);lastUp=0}else {onTap();lastUp=e.eventTime}
    }
    owner="";activeId=-1;performClick()
   }
  };return true
 }
 override fun performClick():Boolean {super.performClick();return true}
}
