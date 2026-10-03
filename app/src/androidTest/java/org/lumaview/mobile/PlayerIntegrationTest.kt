package org.lumaview.mobile
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.lumaview.mobile.ui.PlayerActivity
import org.lumaview.mobile.player.PlayerSession
import org.lumaview.mobile.export.*
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlayerIntegrationTest {
 private val inst=InstrumentationRegistry.getInstrumentation()
 private val context=inst.targetContext
 private lateinit var activity:Activity
 private lateinit var session:PlayerSession
 private val evidence=File(context.filesDir,"integration").apply{mkdirs()}
 private fun waitFor(label:String,limit:Long=40000,check:()->Boolean){val end=SystemClock.elapsedRealtime()+limit;while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(150)};error("TIMEOUT: $label / ${if(::session.isInitialized)session.state else "no session"}")}
 private fun value(name:String):Any?=activity.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(activity)
 private fun find(label:String):View {var found:View?=null;inst.runOnMainSync{fun walk(v:View){if(v is TextView&&v.text.toString()==label)found=v;if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))};walk(activity.window.decorView)};return found?:error("UI element absent: $label")}
 private fun bounds(v:View):Rect {val r=Rect();inst.runOnMainSync{v.getGlobalVisibleRect(r)};check(r.width()>0&&r.height()>0){"element is off screen"};return r}
 private fun touch(x:Float,y:Float,action:Int,time:Long,down:Long){val e=MotionEvent.obtain(down,time,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(inst.uiAutomation.injectInputEvent(e,true));e.recycle()}
 private fun tap(v:View){val b=bounds(v);val d=SystemClock.uptimeMillis();touch(b.exactCenterX(),b.exactCenterY(),0,d,d);touch(b.exactCenterX(),b.exactCenterY(),1,d+55,d);Thread.sleep(300)}
 private fun tap(label:String)=tap(find(label))
 private fun dialogTap(label:String){
  waitFor("dialog: $label",10000){inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.isNotEmpty()==true}
  val n=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).first()
  val b=Rect();n.getBoundsInScreen(b);val d=SystemClock.uptimeMillis()
  touch(b.exactCenterX(),b.exactCenterY(),0,d,d);touch(b.exactCenterX(),b.exactCenterY(),1,d+55,d);Thread.sleep(400)
 }
 private fun comparison(value:Boolean){
  tap("画面增强");Thread.sleep(400)
  val nodes=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("原画对比（选区与倍数不变）")
  assertTrue(nodes.isNotEmpty());if(nodes[0].isChecked!=value)dialogTap("原画对比（选区与倍数不变）")
  dialogTap("完成");waitFor("comparison receipt"){session.state.receipt?.get(5)?.let{if(value)it==0.0 else it==1.0}==true}
 }
 private fun screen(name:String){inst.uiAutomation.takeScreenshot()?.let{b->File(evidence,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
 private fun drag(x1:Float,y1:Float,x2:Float,y2:Float){val d=SystemClock.uptimeMillis();touch(x1,y1,0,d,d);for(i in 1..12){Thread.sleep(30);touch(x1+(x2-x1)*i/12,y1+(y2-y1)*i/12,2,SystemClock.uptimeMillis(),d)};touch(x2,y2,1,SystemClock.uptimeMillis(),d);Thread.sleep(600)}
 private fun snapshot(name:String):File {val out=File(evidence,"$name.png");val latch=CountDownLatch(1);var result:Result<File>?=null;session.screenshot(out){result=it;latch.countDown()};assertTrue(latch.await(20,TimeUnit.SECONDS));result!!.getOrThrow();return out}
 private fun mean(file:File):Double {val bitmap=BitmapFactory.decodeFile(file.path)?:error("PNG decode");val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle();return pixels.sumOf{.2126*((it shr 16)and 255)+.7152*((it shr 8)and 255)+.0722*(it and 255)}/pixels.size}
 @Test fun realTouchRenderingAndExports(){
  val input=File(context.filesDir,"baseline.mp4");inst.context.assets.open("baseline.mp4").use{a->input.outputStream().use{a.copyTo(it)}}
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  session=value("session") as PlayerSession
  try{
   waitFor("real native decode"){session.state.width>0&&session.state.durationUs!=null}
   waitFor("GPU enhancement receipt"){session.state.receipt?.get(6)==0.0}
   val receipt=session.state.receipt!!;assertTrue(receipt[3]>0)
   tap("Ⅱ 暂停");waitFor("pause"){session.state.paused}
   val enhanced=snapshot("01-enhanced");screen("ui-player")
   comparison(true)
   val original=snapshot("02-original");val a=mean(original);val b=mean(enhanced);assertTrue("pixel enhancement $a -> $b",b>a*1.12)
   val bar=value("seek") as SeekBar;val sr=bounds(bar);drag(sr.left+sr.width()*.3f,sr.exactCenterY(),sr.left+sr.width()*.65f,sr.exactCenterY());waitFor("seek by touch"){(session.state.positionUs?:0)>6_000_000};assertTrue(session.state.paused)
   val gb=bounds(value("videoFrame") as View);val down=SystemClock.uptimeMillis()
   touch(gb.exactCenterX(),gb.exactCenterY(),MotionEvent.ACTION_DOWN,down,down)
   touch(gb.exactCenterX()+70,gb.exactCenterY(),MotionEvent.ACTION_MOVE,down+80,down)
   Thread.sleep(200);assertEquals(true,value("dragging"))
   touch(gb.exactCenterX()+70,gb.exactCenterY(),MotionEvent.ACTION_CANCEL,down+300,down)
   waitFor("cancelled gesture releases scrub ownership"){value("dragging")==false}
   waitFor("cancelled seek restores pause"){session.state.paused}
   tap("区域放大");assertNull(value("crop"));val vr=bounds(value("videoFrame") as View)
   val vh=minOf(vr.height().toFloat(),vr.width()*9f/16);val cy=vr.exactCenterY()
   drag(vr.left+vr.width()*.25f,cy-vh*.25f,vr.left+vr.width()*.75f,cy+vh*.25f)
   assertTrue((value("applyButton") as Button).isEnabled);tap("应用");waitFor("source crop applied"){
    val c=value("crop") as? RoiRect;val r=session.state.receipt
    c!=null&&r!=null&&kotlin.math.abs(r[8]-c.left)<3&&kotlin.math.abs(r[9]-c.top)<3&&kotlin.math.abs(r[10]-c.right)<3&&kotlin.math.abs(r[11]-c.bottom)<3
   };snapshot("03-roi")
   val roiOriginal=snapshot("roi-original");val cropBefore=value("crop")
   comparison(false);val roiEnhanced=snapshot("roi-enhanced")
   assertEquals(cropBefore,value("crop"));assertTrue("ROI pixels must be enhanced",mean(roiEnhanced)>mean(roiOriginal)*1.10);screen("ui-roi")
   val pausedCount=session.state.receipt!![13]
   repeat(8){session.enhance(EnhanceSettings(manualEv=it/100f));Thread.sleep(100)}
   Thread.sleep(500);assertEquals("Redraws are not new video frames",pausedCount,session.state.receipt!![13],0.0)
   session.enhance(EnhanceSettings());Thread.sleep(400)
   tap("全画面");waitFor("full view restored"){value("crop")==null}
   for(angle in listOf(90,180,270,0)){tap("旋转");waitFor("rotation $angle"){session.state.rotation==angle}}
   tap("1.00×");dialogTap("0.5×");waitFor("slow playback"){session.state.speed==.5};tap("0.50×");dialogTap("1.0×");waitFor("normal speed"){session.state.speed==1.0}
   val vb=bounds(value("volume") as View);drag(vb.left+vb.width()*.2f,vb.exactCenterY(),vb.left+vb.width()*.8f,vb.exactCenterY())
   assertTrue((context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_MUSIC)>0)
   tap("片段剪辑");val timeline=bounds(value("timeline") as View);val density=context.resources.displayMetrics.density;val left=timeline.left+20*density;val right=timeline.right-20*density
   drag(left,timeline.exactCenterY()-19*density,left+(right-left)*.2f,timeline.exactCenterY()-19*density)
   drag(right,timeline.exactCenterY()+20*density,left+(right-left)*.7f,timeline.exactCenterY()+20*density)
   val selected=value("clipRange") as ClipRange;assertTrue(selected.startUs>1_000_000);assertTrue(selected.endUs-selected.startUs>3_000_000)
   screen("ui-clip")
   // Native data path consumes the real UI-selected range; SAF failure injection is separate.
   val fd=android.os.ParcelFileDescriptor.open(input,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
   NativeExporter.prepare();val plan=JSONObject(NativeExporter.analyze(fd.fd,-1,-1,-2,selected.startUs,selected.endUs));assertFalse(plan.toString(),plan.has("error"))
   val copy=File(evidence,"copy.mp4");NativeExporter.prepare();val copyResult=JSONObject(NativeExporter.write(fd.fd,plan.getInt("video"),plan.getInt("audio"),-2,plan.getLong("startUs"),plan.getLong("endUs"),copy.path,"mp4"));assertFalse(copyResult.toString(),copyResult.has("error"));fd.close();assertTrue(copy.length()>0)
   val suspended=CountDownLatch(1);session.suspendForExport{suspended.countDown()};assertTrue(suspended.await(15,TimeUnit.SECONDS))
   val exact=File(evidence,"exact.mp4");val end=CountDownLatch(1);var exactResult:Result<String>?=null;val exporter=ExactExporter(context)
   exporter.start(Uri.fromFile(input),selected,exact,true,2_000_000,{}){r->exactResult=r;end.countDown()};assertTrue(end.await(120,TimeUnit.SECONDS));exactResult!!.getOrThrow();assertTrue(exact.length()>0)
   val report=JSONObject().put("nativeLoaded",true).put("originalLuma",a).put("enhancedLuma",b).put("uiRangeStartUs",selected.startUs).put("uiRangeEndUs",selected.endUs).put("copyPlan",plan).put("copyResult",copyResult).put("exactResult",exactResult!!.getOrThrow()).put("abi",android.os.Build.SUPPORTED_ABIS.joinToString()).put("physicalDevice",false).put("roiOriginalLuma",mean(roiOriginal)).put("roiEnhancedLuma",mean(roiEnhanced)).put("pausedRedrawDoesNotIncrementFrameCounter",true).put("rotations","0,90,180,270").put("slowSpeed",0.5).put("volumePropertyVerified",true).put("cancelledSeekReleasesOwnership",true)
   File(evidence,"RESULT.json").writeText(report.toString(2));session.resumeAfterExport()
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }
 private fun centerMean(file:File):Double {
  val b=BitmapFactory.decodeFile(file.path)?:error("PNG missing");var sum=0.0;var n=0
  for(y in b.height/3 until b.height*2/3)for(x in b.width/3 until b.width*2/3){val c=b.getPixel(x,y);sum+=.2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c);n++}
  b.recycle();return sum/n
 }
 private fun grayFixture(name:String,inside:Int,outside:Int):File {
  // Exercise the product's video contract. Single PNG image streams do not
  // refresh video-crop in the pinned upstream still-image path.
  val asset="roi-gray-$inside-bg-$outside.mp4"
  val f=File(context.filesDir,asset)
  inst.context.assets.open(asset).use{input->f.outputStream().use{input.copyTo(it)}}
  return f
 }
 @Test fun roiPixelsIgnoreOutsideAndRespondInsideWithNewIntents(){
  val frames=listOf(grayFixture("roi-darkbg.png",96,0),grayFixture("roi-whitebg.png",96,255),grayFixture("roi-inside-dark.png",72,255))
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(frames[0])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  session=value("session") as PlayerSession
  val results=org.json.JSONArray()
  try {
   for((i,f) in frames.withIndex()){
    if(i>0){val old=session.state.generation;inst.runOnMainSync{activity.startActivity(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};waitFor("reused activity receives new media"){session.state.generation>old&&session.state.width==640}}
    waitFor("gray native frame"){session.state.width==640&&session.state.receipt!=null}
    session.pause(true);session.viewport(RoiRect(160.0,90.0,480.0,270.0),0)
    val settings=EnhanceSettings(mode=1,shadows=0f,denoise=0f,detail=0f)
    session.enhance(settings,true)
    waitFor("ROI receipt"){val r=session.state.receipt;r!=null&&r[5]==1.0&&r[6]==0.0&&r[8]==160.0&&r[10]==480.0}
    Thread.sleep(400);val enhanced=centerMean(snapshot("paired-$i-enhanced"))
    session.enhance(settings.copy(bypass=true));waitFor("paired original"){session.state.receipt?.get(5)==0.0}
    Thread.sleep(300);val original=centerMean(snapshot("paired-$i-original"))
    assertTrue(enhanced>original+5)
    results.put(JSONObject().put("inputName",f.name).put("original",original).put("enhanced",enhanced).put("gainRatio",enhanced/original))
   }
   val a=results.getJSONObject(0);val b=results.getJSONObject(1);val c=results.getJSONObject(2)
   val difference=kotlin.math.abs(a.getDouble("enhanced")-b.getDouble("enhanced"))
   assertTrue("Far outside changes must not alter ROI output: $difference",difference<=1.0)
   assertTrue("Darker inside must change automatic gain",c.getDouble("gainRatio")>b.getDouble("gainRatio")*1.15)
   File(evidence,"ROI_PAIR_RESULT.json").writeText(JSONObject().put("samples",results).put("outsideOutputDelta0to255",difference).put("insideGainResponds",true).put("newIntentsReusedActivity",true).put("graphicsScope","Android x86_64 emulator").toString(2))
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1200)}
 }
}
