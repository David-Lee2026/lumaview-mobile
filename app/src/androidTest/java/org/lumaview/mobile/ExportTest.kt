package org.lumaview.mobile

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportTest {
    @Test fun nativeCopyClosedGopAndPreciseReencoding() {
        val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
        val src=File(ctx.filesDir,"export-fixture.mp4")
        ins.context.assets.open("fixture.mp4").use { s->src.outputStream().use{s.copyTo(it)} }
        val token=NativeExport.begin()
        val probe=JSONObject(NativeExport.probe(src.absolutePath,1_020_000,2_110_000,-1,token))
        assertFalse(probe.toString(),probe.has("error"))
        assertEquals(1_000_000,probe.getLong("startUs"))
        assertEquals(3_000_000,probe.getLong("endUs"))
        val copy=File(ctx.filesDir,"copy-export.mp4")
        val remux=JSONObject(NativeExport.remux(src.absolutePath,copy.absolutePath,1_020_000,2_110_000,probe.getInt("videoIndex"),1,token))
        assertFalse(remux.toString(),remux.has("error"));assertTrue(copy.length()>0)
        val cancelledToken=NativeExport.begin();NativeExport.cancel()
        val cancelled=File(ctx.filesDir,"cancelled.mp4")
        assertTrue(JSONObject(NativeExport.remux(src.absolutePath,cancelled.absolutePath,0,1_000_000,-1,-1,cancelledToken)).has("error"))
        assertFalse(cancelled.exists())
        val precise=File(ctx.filesDir,"precise-export.mp4");val latch=CountDownLatch(1)
        var report:String?=null;var error:String?=null
        ins.runOnMainSync { PreciseExport(ctx).start(src,precise,ClipRange(1_033_333,3_111_111),true){r,e->report=r;error=e;latch.countDown()} }
        assertTrue("Transformer timeout",latch.await(90,TimeUnit.SECONDS));assertNull(error)
        val actual=JSONObject(report!!)
        assertTrue(actual.getInt("videoFrames")>0)
        assertTrue(actual.getString("videoEncoder").isNotBlank())
        val evidence=JSONObject().put("probe",probe).put("remux",remux).put("precise",actual).put("cancelBeforeQueuedEntry",!cancelled.exists()).put("huaweiPhysicalValidation","NOT_RUN")
        File(ctx.filesDir,"export-evidence.json").writeText(evidence.toString(2))
    }
}
