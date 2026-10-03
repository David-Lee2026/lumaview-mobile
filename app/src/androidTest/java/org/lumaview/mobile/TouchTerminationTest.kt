package org.lumaview.mobile

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.lumaview.mobile.viewport.RoiOverlayView
import org.lumaview.mobile.ui.ClipTimelineView

/** Android View dispatch tests; not a substitute for physical touchscreen validation. */
@RunWith(AndroidJUnit4::class)
class TouchTerminationTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private fun dispatch(v:View,action:Int,x:Float,y:Float,t:Long) {
  val e=MotionEvent.obtain(1000L,t,action,x,y,0)
  try { assertTrue(v.dispatchTouchEvent(e)) } finally { e.recycle() }
 }
 private fun frame(v:View) {
  val parent=FrameLayout(inst.targetContext);parent.addView(v)
  parent.measure(View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY))
  parent.layout(0,0,640,360);v.layout(0,0,640,360)
 }
 @Test fun cancelledSeekHasExactlyOneTerminalEvent() { inst.runOnMainSync {
  val v=RoiOverlayView(inst.targetContext);frame(v);v.math=RoiMath(640,360,640,360,0,1.0)
  val events=mutableListOf<Pair<Float,Boolean>>()
  var unrelated=0;v.onSeek={f,end->events.add(f to end)};v.onVolume={unrelated++};v.onBrightness={unrelated++};v.onViewport={unrelated++}
  dispatch(v,MotionEvent.ACTION_DOWN,100f,180f,1000)
  dispatch(v,MotionEvent.ACTION_MOVE,260f,180f,1200)
  assertEquals(1,events.size);assertFalse(events[0].second)
  dispatch(v,MotionEvent.ACTION_CANCEL,260f,180f,1300)
  assertEquals("CANCEL must close the in-progress scrub so playback is not stuck paused",1,events.count{it.second})
  assertEquals(.25f,events.last().first,.001f)
  dispatch(v,MotionEvent.ACTION_CANCEL,260f,180f,1400)
  assertEquals("Repeated cancellation cannot finalize twice",1,events.count{it.second});assertEquals(0,unrelated)
  dispatch(v,MotionEvent.ACTION_DOWN,100f,180f,2000)
  dispatch(v,MotionEvent.ACTION_MOVE,180f,180f,2200)
  dispatch(v,MotionEvent.ACTION_UP,180f,180f,2300)
  assertEquals("A later gesture must work normally",2,events.count{it.second})
 } }
 @Test fun secondPointerTerminatesThePreviousSeek() { inst.runOnMainSync {
  val v=RoiOverlayView(inst.targetContext);frame(v);v.math=RoiMath(640,360,640,360,0,1.0)
  val events=mutableListOf<Pair<Float,Boolean>>();v.onSeek={f,end->events.add(f to end)}
  dispatch(v,MotionEvent.ACTION_DOWN,100f,180f,1000);dispatch(v,MotionEvent.ACTION_MOVE,260f,180f,1200)
  val properties=Array(2){MotionEvent.PointerProperties().apply{id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}
  val coords=Array(2){i->MotionEvent.PointerCoords().apply{x=260f+i*100f;y=180f;pressure=1f;size=1f}}
  val e=MotionEvent.obtain(1000,1300,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
  try{assertTrue(v.dispatchTouchEvent(e))}finally{e.recycle()}
  assertEquals("Pinch cannot leave the old scrub captured",1,events.count{it.second})
 } }
 @Test fun cancelledClipDragCommitsOneTerminalPreview() { inst.runOnMainSync {
  val v=ClipTimelineView(inst.targetContext);frame(v);v.durationUs=10_000_000;v.range=ClipRange(0,10_000_000)
  val events=mutableListOf<Pair<ClipRange,Boolean>>();v.onChange={r,end->events.add(r to end)}
  dispatch(v,MotionEvent.ACTION_DOWN,20f,140f,1000);dispatch(v,MotionEvent.ACTION_MOVE,200f,140f,1200)
  assertTrue(events.isNotEmpty());assertTrue(v.range.valid(v.durationUs))
  dispatch(v,MotionEvent.ACTION_CANCEL,200f,140f,1300)
  assertEquals("CANCEL must flush the last clip preview",1,events.count{it.second})
  assertEquals(v.range,events.last().first)
  dispatch(v,MotionEvent.ACTION_CANCEL,200f,140f,1400);assertEquals(1,events.count{it.second})
 } }
}
