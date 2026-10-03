package org.lumaview.mobile

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun letterboxIsNotSelectable() {
        val map = RoiMath(1920, 1080, 1000, 1000)
        assertNull(map.point(100.0, 50.0))
        assertEquals(960.0, map.point(500.0, 500.0)!!.first, 0.001)
    }
    @Test fun reverseDragAndMinimumSize() {
        val map = RoiMath(1920, 1080, 1000, 1000)
        val r = map.select(750.0, 600.0, 250.0, 400.0, 24.0)!!
        assertTrue(r.left < r.right && r.top < r.bottom)
        assertNull(map.select(500.0, 500.0, 505.0, 505.0, 24.0))
        assertEquals("960x384+480+348", r.crop())
    }
    @Test fun clipRejectsReversedUnknownAndNonFiniteRanges() {
        assertFalse(ClipRange(2_000_000, 1_000_000).valid(3_000_000))
        assertFalse(ClipRange(0, 1_000_000).valid(null))
        assertTrue(ClipRange(0, 1_000_000).valid(1_000_000))
    }
    @Test fun speedAndPauseLabelsUseActualState() {
        assertEquals("暂停 · 0.25× 慢速", playbackLabel(true, 0.25))
        assertEquals("播放 · 2.00× 快速", playbackLabel(false, 2.0))
    }
    @Test fun logarithmicStatisticsKeepDarkValuesAndSeparateRoi() {
        val dark = DoubleArray(256) { 0.01 }
        val bright = DoubleArray(256) { 0.8 }
        assertTrue(ExposureReference.target(dark, 4.0) > 3.9)
        assertEquals(0.0, ExposureReference.target(bright, 4.0), 0.001)
        assertTrue(ExposureReference.target(dark + bright, 4.0) < ExposureReference.target(dark, 4.0))
        assertTrue(ExposureReference.target(DoubleArray(256), 4.0).isFinite())
    }
    @Test fun exposureTimeConstantIsFrameRateIndependent() {
        var a = 0.0; var b = 0.0
        repeat(30) { a = ExposureReference.smooth(a, 2.0, 1.0 / 30.0) }
        repeat(60) { b = ExposureReference.smooth(b, 2.0, 1.0 / 60.0) }
        assertTrue(a > .9 && b > .9)
        assertEquals(a, b, 0.025)
    }
    @Test fun highlightMappingIsMonotonicAndFinite() {
        var prev = -1.0
        for (i in 0..1000) {
            val v = ExposureReference.tone(i / 1000.0, 4.0)
            assertTrue(v.isFinite() && v >= prev && v <= 1.0)
            prev = v
        }
        assertEquals(0.0, ExposureReference.tone(0.0, 4.0), 0.0)
        assertEquals(1.0, ExposureReference.tone(1.0, 4.0), 0.000001)
    }
    @Test fun croppedAnamorphicRotationsMapBackToTheSameSourcePixel() {
        val aperture=RoiRect(120.0,60.0,1800.0,1020.0)
        for (rotation in listOf(0,90,180,270)) {
            val map=RoiMath(1920,1080,1000,700,rotation,1.5,aperture,aperture)
            val screen=map.screen(420.0,300.0);val source=map.point(screen.first,screen.second)!!
            assertEquals(420.0,source.first,.001);assertEquals(300.0,source.second,.001)
            assertNull(map.point(map.left-1,map.top+10))
        }
    }
    @Test fun cropAlignmentIsSharedWithTheViewportAndStaysInsideAperture() {
        val aperture=RoiRect(121.0,61.0,1799.0,1019.0)
        val actual=RoiRect(121.2,61.8,1798.7,1018.9).forCrop(aperture)!!
        assertEquals(RoiRect(122.0,62.0,1798.0,1018.0),actual)
        assertEquals("1676x956+122+62",actual.crop())
    }

}
