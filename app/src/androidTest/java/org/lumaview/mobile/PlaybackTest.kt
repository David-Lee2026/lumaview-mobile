package org.lumaview.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import `is`.xyz.mpv.MPVLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackTest {
    private fun waitFor(reason:String,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(100)};fail(reason)}
    @Test fun actualNativePlaybackPauseSpeedRoiAndPixels(){
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        val src=File(ctx.filesDir,"automated-fixture.mp4")
        ins.context.assets.open("fixture.mp4").use { s->src.outputStream().use{s.copyTo(it)} }
        val activity=ins.startActivitySync(Intent(ctx,LumaActivity::class.java).setData(Uri.fromFile(src)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LumaActivity
        waitFor("libmpv did not load/decode fixture"){MPVLib.getPropertyString("path")==src.absolutePath&&(MPVLib.getPropertyInt("video-params/w")?:0)>0&&(MPVLib.getPropertyDouble("time-pos")?:0.0)>0.2}
        val width=MPVLib.getPropertyInt("video-params/w")!!
        MPVLib.setPropertyBoolean("pause",true);waitFor("pause was not applied"){MPVLib.getPropertyBoolean("pause")==true}
        val stopped=MPVLib.getPropertyDouble("time-pos")!!;SystemClock.sleep(350)
        val afterPause=MPVLib.getPropertyDouble("time-pos")!!
        assertEquals(stopped,afterPause,0.12)
        MPVLib.setPropertyDouble("speed",.25);assertEquals(.25,MPVLib.getPropertyDouble("speed")!!,0.001)
        MPVLib.command(arrayOf("seek","1","absolute+exact"));waitFor("exact seek not applied"){kotlin.math.abs((MPVLib.getPropertyDouble("time-pos")?:0.0)-1)<.15}
        val root=activity.window.decorView
        fun surface(v:android.view.View):LumaPlayerView?{if(v is LumaPlayerView)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount){val found=surface(v.getChildAt(i));if(found!=null)return found};return null}
        val view=surface(root)!!
        fun capture(name:String):Bitmap{val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val latch=CountDownLatch(1);var code=-1;ins.runOnMainSync{PixelCopy.request(view,bitmap,{r->code=r;latch.countDown()},Handler(Looper.getMainLooper()))};assertTrue(latch.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,code);File(ctx.filesDir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};return bitmap}
        fun mean(b:Bitmap):Double{var total=0.0;var count=0;for(y in b.height/3 until b.height*2/3 step 8)for(x in b.width/3 until b.width*2/3 step 8){val p=b.getPixel(x,y);total+=((p shr 16)and 255)*.2126+((p shr 8)and 255)*.7152+(p and 255)*.0722;count++};return total/count}
        MPVLib.setPropertyString("lvm-params","0 0 0.5 0 1 0.3 0 100 0 0");SystemClock.sleep(400);val before=capture("original.png")
        MPVLib.setPropertyString("lvm-params","3 0 0.5 0 1 0.3 0 101 0 0");SystemClock.sleep(400);val after=capture("enhanced.png")
        val old=mean(before);val enhanced=mean(after);assertTrue("GPU pixel enhancement did not brighten the dark fixture: $old -> $enhanced",enhanced>old+5)
        fun find(v:android.view.View,predicate:(android.view.View)->Boolean):android.view.View?{if(predicate(v))return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount){val f=find(v.getChildAt(i),predicate);if(f!=null)return f};return null}
        fun click(label:String){ins.runOnMainSync{val button=find(root){it is android.widget.Button && it.text.toString()==label};assertNotNull(label,button);assertTrue(button!!.performClick())}}
        click("区域放大")
        val roi=find(root){it is RoiOverlay} as RoiOverlay
        assertTrue(roi.armed);assertNull(roi.rect)
        val map=RoiMath(640,360,roi.width,roi.height)
        val x1=(map.offsetX+160*map.scale).toFloat();val y1=(map.offsetY+90*map.scale).toFloat()
        val x2=(map.offsetX+480*map.scale).toFloat();val y2=(map.offsetY+270*map.scale).toFloat()
        val down=SystemClock.uptimeMillis()
        fun touch(action:Int,x:Float,y:Float){ins.runOnMainSync{val e=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(roi.dispatchTouchEvent(e));e.recycle()}}
        touch(android.view.MotionEvent.ACTION_DOWN,x1,y1)
        repeat(10){i->touch(android.view.MotionEvent.ACTION_MOVE,x1+(x2-x1)*(i+1)/10,y1+(y2-y1)*(i+1)/10)}
        touch(android.view.MotionEvent.ACTION_UP,x2,y2)
        assertNotNull(roi.rect);click("应用选区");assertFalse(roi.armed)
        waitFor("ROI UI did not apply crop"){MPVLib.getPropertyString("video-crop")?.contains("x")==true}
        val crop=MPVLib.getPropertyString("video-crop")
        val dimensions=Regex("(\\d+)x(\\d+)").find(crop!!)?.groupValues!!
        assertEquals(320.0,dimensions[1].toDouble(),2.0);assertEquals(180.0,dimensions[2].toDouble(),2.0)
        ins.uiAutomation.takeScreenshot()?.let{shot->File(ctx.filesDir,"controls-and-roi.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
        MPVLib.setPropertyDouble("speed",2.0);assertEquals(2.0,MPVLib.getPropertyDouble("speed")!!,0.001)
        val report=JSONObject().put("physicalOrEmulator","emulator").put("api",android.os.Build.VERSION.SDK_INT).put("abi",android.os.Build.SUPPORTED_ABIS.joinToString()).put("nativeDecodedWidth",width).put("pauseStable",kotlin.math.abs(stopped-afterPause)<.12).put("originalMean",old).put("enhancedMean",enhanced).put("crop",crop).put("roiTouchEventsDispatched",true).put("roiButtonDidNotCreateZeroArea",true).put("huaweiPhysicalValidation","NOT_RUN")
        File(ctx.filesDir,"playback-evidence.json").writeText(report.toString(2))
        ins.runOnMainSync{activity.finish()}
    }
    @Test fun roiGpuIgnoresOutsideBrightnessAndRespondsInside() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        val src=File(ctx.filesDir,"roi-fixture.mp4")
        ins.context.assets.open("roi-fixture.mp4").use{s->src.outputStream().use{s.copyTo(it)}}
        val activity=ins.startActivitySync(Intent(ctx,LumaActivity::class.java).setData(Uri.fromFile(src)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LumaActivity
        waitFor("ROI fixture did not load"){MPVLib.getPropertyString("path")==src.absolutePath&&(MPVLib.getPropertyDouble("time-pos")?:0.0)>.1}
        MPVLib.setPropertyBoolean("pause",true)
        MPVLib.setPropertyString("video-crop","192x128+224+116")
        fun surface(v:android.view.View):LumaPlayerView?{if(v is LumaPlayerView)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount){val f=surface(v.getChildAt(i));if(f!=null)return f};return null}
        val view=surface(activity.window.decorView)!!
        fun measure(at:Double,mode:Int,epoch:Int,name:String):Double {
            MPVLib.command(arrayOf("seek",at.toString(),"absolute+exact"))
            waitFor("ROI seek failed"){kotlin.math.abs((MPVLib.getPropertyDouble("time-pos")?:0.0)-at)<.1}
            MPVLib.setPropertyString("lvm-params","$mode 0 0.5 0 1 0 0 $epoch 0 0")
            SystemClock.sleep(600)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val latch=CountDownLatch(1);var code=-1
            ins.runOnMainSync{PixelCopy.request(view,bitmap,{c->code=c;latch.countDown()},Handler(Looper.getMainLooper()))}
            assertTrue(latch.await(10,TimeUnit.SECONDS));assertEquals(PixelCopy.SUCCESS,code)
            var sum=0.0;var n=0
            for(y in bitmap.height*2/5 until bitmap.height*3/5 step 5)for(x in bitmap.width*2/5 until bitmap.width*3/5 step 5){sum+=(bitmap.getPixel(x,y) shr 16)and 255;n++}
            File(ctx.filesDir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            return sum/n
        }
        val originalDark=measure(.5,0,301,"roi-original-dark.png")
        val outsideDark=measure(.5,3,302,"roi-bg-dark.png")
        val outsideBright=measure(2.5,3,303,"roi-bg-bright.png")
        val originalBright=measure(4.5,0,304,"roi-original-bright.png")
        val insideBright=measure(4.5,3,305,"roi-inside-bright.png")
        val outsideEvDifference=2.2*kotlin.math.abs(kotlin.math.log2(outsideBright/outsideDark))
        assertTrue("ROI changed with outside brightness: $outsideEvDifference EV",outsideEvDifference<=.05)
        assertTrue("Fixed exposure did not adapt to ROI brightness",outsideDark/originalDark>insideBright/originalBright+.3)
        File(ctx.filesDir,"roi-adaptation-evidence.json").writeText(JSONObject().put("originalDark",originalDark).put("enhancedOutsideDark",outsideDark).put("enhancedOutsideBright",outsideBright).put("originalBright",originalBright).put("enhancedInsideBright",insideBright).put("outsideEvDifference",outsideEvDifference).put("measurement","gamma-2.2 output ratio proxy; not a native EV receipt").put("huaweiPhysicalValidation","NOT_RUN").toString(2))
        ins.runOnMainSync{activity.finish()}
    }

}
