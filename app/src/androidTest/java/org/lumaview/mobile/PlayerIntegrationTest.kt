package org.lumaview.mobile
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
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
 private fun drag(x1:Float,y1:Float,x2:Float,y2:Float){val d=SystemClock.uptimeMillis();touch(x1,y1,0,d,d);for(i in 1..12){Thread.sleep(30);touch(x1+(x2-x1)*i/12,y1+(y2-y1)*i/12,2,SystemClock.uptimeMillis(),d)};touch(x2,y2,1,SystemClock.uptimeMillis(),d);Thread.sleep(600)}
 private fun snapshot(name:String):File {val out=File(evidence,"$name.png");val latch=CountDownLatch(1);var result:Result<File>?=null;session.screenshot(out){result=it;latch.countDown()};assertTrue(latch.await(20,TimeUnit.SECONDS));result!!.getOrThrow();return out}
 // This captures the displayed window, rather than mpv's off-screen framebuffer.
 private fun screenSnapshot(name:String):File {
  inst.waitForIdleSync();Thread.sleep(350)
  val screen=inst.uiAutomation.takeScreenshot()?:error("display screenshot unavailable")
  File(evidence,"$name-window.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}
  val frame=bounds(value("videoFrame") as View)
  val fitted=RoiMath(session.state.width,session.state.height,frame.width(),frame.height(),session.state.rotation,session.state.sar)
  val left=(frame.left+fitted.left).toInt().coerceIn(0,screen.width-1);val top=(frame.top+fitted.top).toInt().coerceIn(0,screen.height-1)
  val bitmap=Bitmap.createBitmap(screen,left,top,fitted.displayWidth.toInt().coerceIn(1,screen.width-left),fitted.displayHeight.toInt().coerceIn(1,screen.height-top))
  val out=File(evidence,"$name.png");out.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();screen.recycle();return out
 }
 private fun mean(file:File):Double {val bitmap=BitmapFactory.decodeFile(file.path)?:error("PNG decode");val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle();return pixels.sumOf{.2126*((it shr 16)and 255)+.7152*((it shr 8)and 255)+.0722*(it and 255)}/pixels.size}
 private fun displayedProgressRight(window:File):Int {
  val bitmap=BitmapFactory.decodeFile(window.path)?:error("window PNG decode");val bar=bounds(value("seek") as View);var right=-1
  for(y in bar.top until bar.bottom.coerceAtMost(bitmap.height))for(x in bar.left until bar.right.coerceAtMost(bitmap.width)){
   val p=bitmap.getPixel(x,y);val r=(p shr 16)and 255;val g=(p shr 8)and 255;val b=p and 255
   if(g>140&&b>100&&g-r>40&&b-r>30)right=maxOf(right,x)
  }
  bitmap.recycle();assertTrue("progress thumb must actually be visible in the system window",right>=0);return right
 }
 @Test fun realTouchRenderingAndExports(){
  val input=File(context.filesDir,"baseline.mp4");inst.context.assets.open("baseline.mp4").use{a->input.outputStream().use{a.copyTo(it)}}
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  session=value("session") as PlayerSession
  try{
   waitFor("real native decode"){session.state.width>0&&session.state.durationUs!=null}
   waitFor("GPU enhancement receipt"){session.state.receipt?.get(6)==0.0}
   val receipt=session.state.receipt!!;assertTrue(receipt[3]>0)
   tap("Ⅱ 暂停");waitFor("pause"){session.state.paused}
   val enhanced=snapshot("01-enhanced")
   val displayedEnhanced=screenSnapshot("screen-enhanced");assertTrue("displayed video must not be black",mean(displayedEnhanced)>4.0)
   tap("画面增强")
   // Dialog views have their own window; use real system accessibility input to activate it.
   val root=inst.uiAutomation.rootInActiveWindow
   val checks=root.findAccessibilityNodeInfosByText("原画对比（选区与倍数不变）");assertTrue(checks.isNotEmpty());val br=Rect();checks[0].getBoundsInScreen(br);val d=SystemClock.uptimeMillis();touch(br.exactCenterX(),br.exactCenterY(),0,d,d);touch(br.exactCenterX(),br.exactCenterY(),1,d+55,d)
   Thread.sleep(400);val done=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("完成");assertTrue(done.isNotEmpty());done[0].getBoundsInScreen(br);val d2=SystemClock.uptimeMillis();touch(br.exactCenterX(),br.exactCenterY(),0,d2,d2);touch(br.exactCenterX(),br.exactCenterY(),1,d2+55,d2)
   waitFor("original comparison receipt"){session.state.receipt?.get(5)==0.0}
   val original=snapshot("02-original");val a=mean(original);val b=mean(enhanced);assertTrue("pixel enhancement $a -> $b",b>a*1.12)
   val displayedOriginal=screenSnapshot("screen-original");assertTrue("original must reach the displayed window",mean(displayedOriginal)>4.0);assertTrue("enhancement must visibly change the displayed video",mean(displayedEnhanced)>mean(displayedOriginal)*1.12)
   val bar=value("seek") as SeekBar;val sr=bounds(bar);drag(sr.left+sr.width()*.3f,sr.exactCenterY(),sr.left+sr.width()*.65f,sr.exactCenterY());waitFor("seek by touch"){(session.state.positionUs?:0)>6_000_000};assertTrue(session.state.paused)
   tap("区域放大");assertNull(value("crop"));val vr=bounds(value("videoFrame") as View)
   // Work within fitted video, excluding letterbox. The fixture aspect ratio is 16:9.
   val vh=minOf(vr.height().toFloat(),vr.width()*9f/16);val cy=vr.exactCenterY()
   drag(vr.left+vr.width()*.25f,cy-vh*.25f,vr.left+vr.width()*.75f,cy+vh*.25f)
   assertTrue((value("applyButton") as Button).isEnabled);tap("应用");waitFor("source crop applied"){value("crop")!=null};snapshot("03-roi")
   tap("全画面");waitFor("full view restored"){value("crop")==null}
   tap("片段剪辑");val timeline=bounds(value("timeline") as View);val density=context.resources.displayMetrics.density;val left=timeline.left+20*density;val right=timeline.right-20*density
   drag(left,timeline.exactCenterY()-19*density,left+(right-left)*.2f,timeline.exactCenterY()-19*density)
   drag(right,timeline.exactCenterY()+20*density,left+(right-left)*.7f,timeline.exactCenterY()+20*density)
   val selected=value("clipRange") as ClipRange;assertTrue(selected.startUs>1_000_000);assertTrue(selected.endUs-selected.startUs>3_000_000)
   // Native data path tests consume the actual UI-selected range, not invented parameters.
   val acquired=CountDownLatch(1);var authorized:Result<org.lumaview.mobile.storage.ReadLease>?=null;session.readLease{authorized=it;acquired.countDown()};assertTrue(acquired.await(10,TimeUnit.SECONDS));val lease=authorized!!.getOrThrow();val fd=lease.descriptor
   val stale=NativeExporter.begin();NativeExporter.cancel();val cancelled=JSONObject(NativeExporter.analyze(fd.fd,-1,-1,-2,selected.startUs,selected.endUs,stale));assertTrue("cancel before JNI entry must remain cancelled",cancelled.has("error"));val token=NativeExporter.begin();val plan=JSONObject(NativeExporter.analyze(fd.fd,-1,-1,-2,selected.startUs,selected.endUs,token));assertFalse(plan.toString(),plan.has("error"))
   val copy=File(evidence,"copy.mp4");val copyResult=JSONObject(NativeExporter.write(fd.fd,plan.getInt("video"),plan.getInt("audio"),-2,plan.getLong("startUs"),plan.getLong("endUs"),copy.path,"mp4",token));assertFalse(copyResult.toString(),copyResult.has("error"));lease.close();assertTrue(copy.length()>0)
   val suspended=CountDownLatch(1);session.suspendForExport{suspended.countDown()};assertTrue(suspended.await(15,TimeUnit.SECONDS))
   val exact=File(evidence,"exact.mp4");val end=CountDownLatch(1);var exactResult:Result<String>?=null;val exporter=ExactExporter(context)
   exporter.start(Uri.fromFile(input),selected,exact,true,2_000_000,{}){r->exactResult=r;end.countDown()};assertTrue(end.await(120,TimeUnit.SECONDS));exactResult!!.getOrThrow();assertTrue(exact.length()>0)
   val report=JSONObject().put("nativeLoaded",true).put("originalLuma",a).put("enhancedLuma",b).put("uiRangeStartUs",selected.startUs).put("uiRangeEndUs",selected.endUs).put("copyPlan",plan).put("copyResult",copyResult).put("exactResult",exactResult!!.getOrThrow()).put("abi",android.os.Build.SUPPORTED_ABIS.joinToString()).put("physicalDevice",false)
   File(evidence,"RESULT.json").writeText(report.toString(2));session.resumeAfterExport()
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }

 @Test fun rendererFailureRecoversVisibleVideo(){
  val input=File(context.filesDir,"baseline.mp4");inst.context.assets.open("baseline.mp4").use{src->input.outputStream().use{src.copyTo(it)}}
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=value("session") as PlayerSession
  try {
   waitFor("initial displayed video"){session.state.receipt?.get(6)==0.0};session.pause(true);waitFor("pause before render fault"){session.state.paused}
   val before=mean(screenSnapshot("screen-before-fault"));assertTrue(before>4.0)
   val beforeRequest=session.state.receipt!![3]
   val bad=File(context.filesDir,"deliberately-invalid.glsl");bad.writeText("//!HOOK MAIN\n//!BIND HOOKED\nvec4 hook(){ return intentionally_invalid_shader; }\n")
   MPVLib.setPropertyString("glsl-shaders",bad.path);session.enhance(EnhanceSettings(),true)
   // A bad enhancement program must not strand playback at a broken frame.
   waitFor("recover shader failure",20000){session.state.receipt?.let{it[6]==0.0&&it[5]>0&&it[3]>beforeRequest+1}==true}
   val restored=mean(screenSnapshot("screen-after-fault"));assertTrue("failed shader must recover visible enhanced video",restored>4.0&&restored/before in .7..1.3)
   val nextRequest=session.state.receipt!![3];MPVLib.setPropertyString("glsl-shaders",bad.path);session.enhance(EnhanceSettings(),true)
   waitFor("second shader failure restores original",20000){session.state.rendererTier==2&&session.state.receipt?.let{it[6]==0.0&&it[5]==0.0&&it[3]>nextRequest+1}==true}
   val raw=mean(screenSnapshot("screen-after-second-fault"));assertTrue("original must remain visible after both enhancement paths fail",raw>4.0&&raw<restored*.9)
   File(evidence,"RECOVERY_RESULT.json").writeText(JSONObject().put("displayedBefore",before).put("displayedAfter",restored).put("displayedOriginalAfterSecondFault",raw).put("recovered",true).put("originalFallbackVisible",true).put("physicalDevice",false).toString(2))
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }
 @Test fun progressDoesNotJumpToZeroForMissingPositionSample(){
  val input=File(context.filesDir,"baseline.mp4");inst.context.assets.open("baseline.mp4").use{src->input.outputStream().use{src.copyTo(it)}}
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=value("session") as PlayerSession
  try{
   waitFor("progress playback"){(session.state.positionUs?:0)>1_000_000&&session.state.durationUs!=null}
   // Keep the measured row visible through the application's real menu;
   // normal auto-hide may otherwise occur between the two screenshots.
   tap("更多");val visible=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("控制栏常显／自动隐藏");assertTrue(visible.isNotEmpty());assertTrue(visible[0].performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(300)
   inst.runOnMainSync{
    val bar=value("seek") as SeekBar;val previous=bar.progress;assertTrue(previous>0)
    activity.javaClass.getDeclaredMethod("render",org.lumaview.mobile.player.PlayerState::class.java).apply{isAccessible=true}.invoke(activity,session.state.copy(positionUs=null))
    assertTrue("a missing playback sample must not reset the thumb",bar.progress>=previous)
   }
   screenSnapshot("screen-progress-start");val firstPixel=displayedProgressRight(File(evidence,"screen-progress-start-window.png"))
   val samples=org.json.JSONArray();var last=-1;var top:Int?=null
   repeat(30){Thread.sleep(50);inst.runOnMainSync{val bar=value("seek") as SeekBar;val location=IntArray(2);bar.getLocationOnScreen(location);assertTrue("live progress must not jitter backward",bar.progress>=last);if(top!=null)assertEquals("progress row must not shake vertically",top!!,location[1]);last=bar.progress;top=location[1];samples.put(JSONObject().put("progress",last).put("top",top))}}
   screenSnapshot("screen-progress-end");val lastPixel=displayedProgressRight(File(evidence,"screen-progress-end-window.png"));assertTrue("displayed progress must move forward",lastPixel>firstPixel+20)
   File(evidence,"PROGRESS_RESULT.json").writeText(JSONObject().put("missingSampleKeptPosition",true).put("samples",samples).put("displayedThumbStartX",firstPixel).put("displayedThumbEndX",lastPixel).put("physicalDevice",false).toString(2))
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }
 @Test fun decoderSwitchesAndExposureSettingsReachTheDisplayedWindow(){
  context.getSharedPreferences("playback",0).edit().clear().commit()
  val input=File(context.filesDir,"baseline.mp4");inst.context.assets.open("baseline.mp4").use{src->input.outputStream().use{src.copyTo(it)}}
  context.getSharedPreferences("history",0).edit().putLong("position:${Uri.fromFile(input)}",0).commit()
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=value("session") as PlayerSession
  try{
   waitFor("software compatibility decode"){(session.state.positionUs?:0)>1_000_000&&session.state.decoder.endsWith("/ no")&&session.state.receipt?.get(6)==0.0}
   session.pause(true);waitFor("compatibility pause"){session.state.paused};val software=mean(screenSnapshot("screen-software"));assertTrue(software>4.0)
   fun choose(label:String){tap("解码设置");val nodes=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label);assertTrue(nodes.isNotEmpty());assertTrue(nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(500)}
   val decoderRequest=session.state.receipt!![3]
   choose("硬件加速（复制解码）");waitFor("copy-back decoder or explicit fallback"){session.state.decoderMode=="mediacodec-copy"&&session.state.receipt?.let{it[3]>decoderRequest&&it[6]==0.0}==true&&(session.state.decoder.endsWith("/ mediacodec-copy")||session.state.decoderFallback)}
   val hardwareAvailable=session.state.decoder.endsWith("/ mediacodec-copy")
   if(!hardwareAvailable){
    assertEquals("API35 must exercise actual copy-back decoding",29,android.os.Build.VERSION.SDK_INT)
    val logged=CountDownLatch(1);var diagnostic="";session.diagnostic{diagnostic=it;logged.countDown()};assertTrue(logged.await(5,TimeUnit.SECONDS))
    assertTrue("fallback requires actual native codec failure evidence",diagnostic.contains("Could not open codec")||diagnostic.contains("failed to start"))
    waitFor("decoder fallback notice"){var text="";inst.runOnMainSync{text=(value("status") as TextView).text.toString()};text.contains("硬件不可用，软件解码")}
   }
   val hardware=mean(screenSnapshot("screen-hardware-copy"));assertTrue(hardware>4.0)
   choose("兼容播放（软件解码，默认）");waitFor("return to software decode"){session.state.decoder.endsWith("/ no")&&session.state.receipt?.get(6)==0.0}
   val before=mean(screenSnapshot("screen-exposure-before"));val request=session.state.receipt!![3]
   tap("画面增强");val bars=ArrayList<AccessibilityNodeInfo>()
   fun collect(node:AccessibilityNodeInfo){if(node.className?.toString()=="android.widget.SeekBar")bars.add(node);for(i in 0 until node.childCount)node.getChild(i)?.let{collect(it)}}
   collect(inst.uiAutomation.rootInActiveWindow);assertTrue(bars.isNotEmpty());val args=android.os.Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,150f)}
   assertTrue(bars[0].performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args));Thread.sleep(400)
   inst.runOnMainSync{assertEquals("exposure slider must submit +0.5 EV",.5f,(value("enhancement") as EnhanceSettings).manualEv,.001f)}
   val done=inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("完成");assertTrue(done.isNotEmpty());assertTrue(done[0].performAction(AccessibilityNodeInfo.ACTION_CLICK))
   waitFor("exposure setting render"){session.state.receipt?.let{it[3]>request&&it[6]==0.0}==true}
   val after=mean(screenSnapshot("screen-exposure-after"));assertTrue("exposure setting must change actual displayed pixels",after>before*1.05)
   val oldSurface=session.state.receipt!![1];val oldPosition=session.state.positionUs!!;val oldGeneration=session.state.generation
   assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
   waitFor("player hidden"){var focus=true;inst.runOnMainSync{focus=activity.hasWindowFocus()};!focus};Thread.sleep(1000)
   context.startActivity(Intent(context,PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
   waitFor("holder recreated after returning"){var focus=false;inst.runOnMainSync{focus=activity.hasWindowFocus()};focus&&session.state.generation==oldGeneration&&session.state.receipt?.let{it[1]!=oldSurface&&it[6]==0.0}==true&&kotlin.math.abs((session.state.positionUs?:-10_000_000)-oldPosition)<500_000}
   assertTrue("return must keep playback position",kotlin.math.abs(session.state.positionUs!!-oldPosition)<500_000)
   val returned=mean(screenSnapshot("screen-after-home-return"));assertTrue("recreated holder must display video",returned>4.0)
   File(evidence,"DISPLAY_RESULT.json").writeText(JSONObject().put("api",android.os.Build.VERSION.SDK_INT).put("softwareLuma",software).put("hardwareCopyLuma",hardware).put("hardwareCopyAvailable",hardwareAvailable).put("hardwareSelectionUsedSoftwareFallback",!hardwareAvailable).put("beforeExposureLuma",before).put("afterExposureLuma",after).put("returnedLuma",returned).put("surfaceRecreated",true).put("settingsVisible",true).put("physicalDevice",false).toString(2))
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }
 @Test fun highDefinitionH264AndTenBitHevcDisplayOriginalAndEnhancement(){
  val results=org.json.JSONArray()
  val names=listOf("hd-h264.mp4","hd-hevc10.mp4")
  for(name in names){val file=File(context.filesDir,name);inst.context.assets.open(name).use{src->file.outputStream().use{src.copyTo(it)}}}
  for(name in names){
   context.getSharedPreferences("playback",0).edit().putString("decoder","no").commit()
   val input=File(context.filesDir,name);context.getSharedPreferences("history",0).edit().putLong("position:${Uri.fromFile(input)}",0).commit()
   activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=value("session") as PlayerSession
   try{
    waitFor("1080p decode $name"){session.state.width==1920&&session.state.height==1080&&session.state.receipt?.get(6)==0.0&&session.state.receipt?.get(5)==1.0}
    session.pause(true);waitFor("1080p pause"){session.state.paused};val enhanced=mean(screenSnapshot("screen-$name-enhanced"))
    session.enhance(EnhanceSettings(mode=0),true);waitFor("1080p original"){session.state.receipt?.get(5)==0.0&&session.state.receipt?.get(6)==0.0};val original=mean(screenSnapshot("screen-$name-original"))
    assertTrue("1080p $name original must be visible",original>4.0);assertTrue("1080p $name enhancement must be visible",enhanced>original*1.1)
    results.put(JSONObject().put("name",name).put("width",session.state.width).put("height",session.state.height).put("decoder",session.state.decoder).put("originalLuma",original).put("enhancedLuma",enhanced))
   }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
  }
  File(evidence,"HD_RESULT.json").writeText(JSONObject().put("api",android.os.Build.VERSION.SDK_INT).put("clips",results).put("physicalDevice",false).toString(2))
 }
 @Test fun roiStatisticsIgnoreOutsideBrightnessAndRespondInside(){
  val input=File(context.filesDir,"roi-scenes.mp4");inst.context.assets.open("roi-scenes.mp4").use{src->input.outputStream().use{src.copyTo(it)}}
  activity=inst.startActivitySync(Intent(context,PlayerActivity::class.java).setData(Uri.fromFile(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session=value("session") as PlayerSession
  try{
   waitFor("ROI fixture decode"){session.state.width==640&&session.state.receipt?.get(6)==0.0};session.pause(true);waitFor("ROI fixture pause"){session.state.paused}
   tap("区域放大");val frame=bounds(value("videoFrame") as View);val height=minOf(frame.height().toFloat(),frame.width()*9f/16);val width=height*16f/9;val cx=frame.exactCenterX();val cy=frame.exactCenterY()
   drag(cx-width*.25f,cy-height*.25f,cx+width*.25f,cy+height*.25f);tap("应用")
   fun at(us:Long){session.seek(us);waitFor("ROI scene $us"){val state=session.state;state.receipt?.get(6)==0.0&&kotlin.math.abs((state.positionUs?:-10_000_000)-us)<200_000&&kotlin.math.abs((state.receipt?.get(4)?:-10_000_000.0)-us)<200_000};Thread.sleep(350)}
   at(500_000);val first=mean(snapshot("04-roi-black-background"));session.enhance(EnhanceSettings(mode=0),true);waitFor("ROI original"){session.state.receipt?.get(5)==0.0};val brightRaw=mean(snapshot("05-roi-bright-original"));session.enhance(EnhanceSettings(),true);waitFor("ROI enhancement restored"){session.state.receipt?.get(5)==1.0}
   at(3_500_000);val outside=mean(snapshot("06-roi-white-background"));val outsideVariation=kotlin.math.abs(outside/first-1.0);assertTrue("outside brightness leaked into ROI: $first -> $outside",outsideVariation<.02)
   at(6_500_000);val darkEnhanced=mean(snapshot("07-roi-dark-enhanced"));session.enhance(EnhanceSettings(mode=0),true);waitFor("dark ROI original"){session.state.receipt?.get(5)==0.0};val darkRaw=mean(snapshot("08-roi-dark-original"))
   val brightGain=first/brightRaw;val darkGain=darkEnhanced/darkRaw;assertTrue("ROI must respond when inside becomes darker: $brightGain -> $darkGain",darkGain>brightGain*1.15)
   File(evidence,"ROI_RESULT.json").writeText(JSONObject().put("outsideOutputVariation",outsideVariation).put("brightOutputRatio",brightGain).put("darkOutputRatio",darkGain).put("outsideIndependence",true).put("insideResponse",true).put("physicalDevice",false).toString(2))
  }finally{inst.runOnMainSync{activity.finish()};Thread.sleep(1000)}
 }

}
