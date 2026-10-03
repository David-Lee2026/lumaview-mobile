package org.lumaview.mobile.ui
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import org.lumaview.mobile.*
import kotlin.math.*

class ClipTimelineView(context:Context):View(context){
 var durationUs:Long=0;var range=ClipRange(0,1);var positionUs=0L
 var zoom=1.0;private var windowStart=0L;private var owner=0
 var onChange:(ClipRange,Boolean)->Unit={_,_->}
 private val dp=resources.displayMetrics.density;private val p=Paint(Paint.ANTI_ALIAS_FLAG)
 fun zoomAt(factor:Double){zoom=(zoom*factor).coerceIn(1.0,128.0);val span=(durationUs/zoom).toLong();windowStart=(positionUs-span/2).coerceIn(0,(durationUs-span).coerceAtLeast(0));invalidate()}
 private fun span()=(durationUs/zoom).toLong().coerceAtLeast(1)
 private fun x(us:Long)=20*dp+(us-windowStart).toDouble()/span()*(width-40*dp)
 private fun time(x:Float):Long=(windowStart+((x-20*dp)/(width-40*dp)).coerceIn(0f,1f)*span()).toLong().coerceIn(0,durationUs)
 override fun onDraw(c:Canvas){
  super.onDraw(c);val y=height*.5f;val left=20*dp;val right=width-20*dp
  p.color=0xff435265.toInt();p.strokeWidth=6*dp;c.drawLine(left,y,right,y,p)
  val a=x(range.startUs).toFloat();val b=x(range.endUs).toFloat();p.color=0xff37b8a9.toInt();c.drawLine(a.coerceIn(left,right),y,b.coerceIn(left,right),y,p)
  p.textSize=12*dp;p.textAlign=Paint.Align.CENTER
  for(i in 0..4){val f=i/4f;val xx=left+(right-left)*f;p.color=0xff8c9cb2.toInt();p.strokeWidth=dp;c.drawLine(xx,y+8*dp,xx,y+12*dp,p);c.drawText(formatTime(windowStart+(span()*f).toLong()),xx,height-3*dp,p)}
  for((xx,label,yy,col) in listOf(arrayOf(a,"A",y-19*dp,0xff61b2ff.toInt()),arrayOf(b,"B",y+20*dp,0xffffcf70.toInt()))){
   val px=xx as Float;if(px !in left..right)continue;p.color=col as Int;p.strokeWidth=3*dp;c.drawLine(px,y-10*dp,px,y+10*dp,p);c.drawCircle(px,yy as Float,12*dp,p);p.color=Color.BLACK;p.textSize=13*dp;c.drawText(label as String,px,yy+4*dp,p)
  }
  p.color=Color.WHITE;p.strokeWidth=dp;val cursor=x(positionUs).toFloat();if(cursor in left..right)c.drawLine(cursor,y-8*dp,cursor,y+8*dp,p)
 }
 override fun onTouchEvent(e:MotionEvent):Boolean {
  if(durationUs<=0)return true
  when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{parent.requestDisallowInterceptTouchEvent(true);val a=x(range.startUs);val b=x(range.endUs);owner=if(abs(a-b)<48*dp){if(e.y<height/2)1 else 2}else if(abs(e.x-a)<abs(e.x-b))1 else 2;update(e.x,false)}
   MotionEvent.ACTION_MOVE->if(owner!=0)update(e.x,false)
   MotionEvent.ACTION_UP->{if(owner!=0)update(e.x,true);owner=0;performClick()}
   MotionEvent.ACTION_CANCEL->owner=0
  };return true
 }
 private fun update(x:Float,end:Boolean){val t=time(x);range=if(owner==1)ClipRange(t.coerceAtMost(range.endUs-1),range.endUs)else ClipRange(range.startUs,t.coerceAtLeast(range.startUs+1));onChange(range,end);invalidate()}
 override fun performClick():Boolean{super.performClick();return true}
}
