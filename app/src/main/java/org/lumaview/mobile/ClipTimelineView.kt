package org.lumaview.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Separate vertical hot zones make coincident A/B handles independently reachable. */
class ClipTimelineView(context:Context):View(context) {
    var durationUs=0L;var startUs=0L;var endUs=0L;var positionUs=0L
    var onRange:((Long,Long)->Unit)?=null
    private var drag=0;private var pointer=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=5f;textSize=28f}
    private fun x(time:Long)=20f+(width-40f)*(time.toDouble()/durationUs.coerceAtLeast(1)).toFloat()
    override fun onDraw(c:Canvas){
        val mid=height/2f;paint.color=0xff426181.toInt();c.drawLine(20f,mid,width-20f,mid,paint)
        paint.color=0xff53d8fa.toInt();c.drawLine(x(startUs),mid,x(endUs),mid,paint)
        c.drawCircle(x(startUs),mid-12,10f,paint);c.drawText("A",x(startUs)-8,mid-25,paint)
        paint.color=0xfffeb45b.toInt();c.drawCircle(x(endUs),mid+12,10f,paint);c.drawText("B",x(endUs)-8,height-3f,paint)
        paint.color=0xffeff4ff.toInt();c.drawLine(x(positionUs),mid-8,x(positionUs),mid+8,paint)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(durationUs<=0)return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{pointer=e.getPointerId(0);drag=if(abs(x(startUs)-x(endUs))<48*resources.displayMetrics.density){if(e.y<height/2)1 else 2}else if(abs(e.x-x(startUs))<abs(e.x-x(endUs)))1 else 2;parent.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->{if(e.getPointerId(0)!=pointer)return true;val t=(((e.x-20)/(width-40).coerceAtLeast(1)).coerceIn(0f,1f)*durationUs).toLong();if(drag==1)startUs=t.coerceIn(0,(endUs-1).coerceAtLeast(0))else endUs=t.coerceIn((startUs+1).coerceAtMost(durationUs),durationUs);onRange?.invoke(startUs,endUs);invalidate();if(e.actionMasked==MotionEvent.ACTION_UP){pointer=-1;drag=0;parent.requestDisallowInterceptTouchEvent(false)}}
            MotionEvent.ACTION_CANCEL->{pointer=-1;drag=0;parent.requestDisallowInterceptTouchEvent(false)}
        }
        return true
    }
}
