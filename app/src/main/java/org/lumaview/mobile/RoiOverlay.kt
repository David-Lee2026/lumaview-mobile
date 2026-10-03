package org.lumaview.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class RoiOverlay(context: Context): View(context) {
    var armed=false
    var rect: RectF?=null
    var onChange: (() -> Unit)?=null
    var onPan: ((Float,Float)->Unit)?=null
    var zoomed=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(80,220,255);strokeWidth=3f;style=Paint.Style.STROKE }
    private var pointer=-1;private var x=0f;private var y=0f;private var kind=-1
    override fun onDraw(c:Canvas) { rect?.let { r ->
        c.drawRect(r,paint)
        for(p in listOf(r.left to r.top,r.right to r.top,r.right to r.bottom,r.left to r.bottom))c.drawCircle(p.first,p.second,10f,paint)
    } }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!armed && !zoomed)return false
        if(e.actionMasked==MotionEvent.ACTION_CANCEL){pointer=-1;return true}
        if(e.pointerCount!=1){pointer=-1;return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                pointer=e.getPointerId(0);x=e.x;y=e.y;kind=-1
                if(armed){
                    val r=rect
                    if(r!=null){val corners=listOf(r.left to r.top,r.right to r.top,r.right to r.bottom,r.left to r.bottom)
                        kind=corners.indexOfFirst { hypot(e.x-it.first,e.y-it.second)<32*resources.displayMetrics.density }
                        if(kind<0&&r.contains(e.x,e.y))kind=4
                    }
                    if(kind<0){rect=RectF(x,y,x,y);kind=5}
                }
                return true
            }
            MotionEvent.ACTION_MOVE->{
                if(pointer!=e.getPointerId(0))return true
                if(!armed){onPan?.invoke(e.x-x,e.y-y);x=e.x;y=e.y;return true}
                val r=rect?:return true;val nx=e.x.coerceIn(0f,width.toFloat());val ny=e.y.coerceIn(0f,height.toFloat())
                when(kind){0->{r.left=nx;r.top=ny};1->{r.right=nx;r.top=ny};2->{r.right=nx;r.bottom=ny};3->{r.left=nx;r.bottom=ny};4->{val dx=(nx-x).coerceIn(-r.left,width-r.right);val dy=(ny-y).coerceIn(-r.top,height-r.bottom);r.offset(dx,dy);x=nx;y=ny};5->{r.left=min(x,nx);r.right=max(x,nx);r.top=min(y,ny);r.bottom=max(y,ny)}}
                invalidate();return true
            }
            MotionEvent.ACTION_UP->{pointer=-1;rect?.sort();onChange?.invoke();invalidate();return true}
        }
        return true
    }
}
