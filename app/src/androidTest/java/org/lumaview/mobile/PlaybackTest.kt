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
        waitFor("libmpv did not load/decode fixture"){(MPVLib.getPropertyInt("video-params/w")?:0)>0&&(MPVLib.getPropertyDouble("time-pos")?:0.0)>0.2}
        val width=MPVLib.getPropertyInt("video-params/w")!!
        MPVLib.setPropertyBoolean("pause",true);waitFor("pause was not applied"){MPVLib.getPropertyBoolean("pause")==true}
        val stopped=MPVLib.getPropertyDouble("time-pos")!!;SystemClock.sleep(350)
        assertEquals(stopped,MPVLib.getPropertyDouble("time-pos")!!,0.12)
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
        MPVLib.setPropertyString("video-crop","320x180+160+90");SystemClock.sleep(300);val crop=MPVLib.getPropertyString("video-crop");assertTrue(crop?.contains("320x180")==true)
        MPVLib.setPropertyDouble("speed",2.0);assertEquals(2.0,MPVLib.getPropertyDouble("speed")!!,0.001)
        val report=JSONObject().put("physicalOrEmulator","emulator").put("api",android.os.Build.VERSION.SDK_INT).put("abi",android.os.Build.SUPPORTED_ABIS.joinToString()).put("nativeDecodedWidth",width).put("pauseStable",kotlin.math.abs(stopped-(stopped))<.12).put("originalMean",old).put("enhancedMean",enhanced).put("crop",crop).put("huaweiPhysicalValidation","NOT_RUN")
        File(ctx.filesDir,"playback-evidence.json").writeText(report.toString(2))
        ins.runOnMainSync{activity.finish()}
    }
}
