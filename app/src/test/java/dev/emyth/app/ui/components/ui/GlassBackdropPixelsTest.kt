package dev.emyth.app.ui.components.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassBackdropPixelsTest {
    @Test
    fun largeWallpapersKeepTheirAspectRatioWithinTheMemoryBound() {
        assertEquals(640 to 53, boundedGlassBackdropSize(12_000, 1_000, 640))
        assertEquals(53 to 640, boundedGlassBackdropSize(1_000, 12_000, 640))
        assertEquals(1 to 640, boundedGlassBackdropSize(1, 12_000, 640))
        assertEquals(32 to 16, boundedGlassBackdropSize(32, 16, 640))
    }

    @Test
    fun aUniformTranslucentWallpaperDoesNotChangeColorOrOpacity() {
        val pixels = IntArray(35) { 0x7f1d92e8 }
        assertArrayEquals(pixels, blurGlassBackdropPixels(pixels, 7, 5))
    }

    @Test
    fun anIsolatedBrightPointSoftensInBothDirectionsWithoutDarkeningTheBorder() {
        val pixels = intArrayOf(
            0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(),
            0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt(),
            0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(),
        )
        val expected = IntArray(9) { 0xff1c1c1c.toInt() }
        assertArrayEquals(expected, blurGlassBackdropPixels(pixels, 3, 3, radius = 1))
    }

    @Test
    fun invisibleColorDoesNotBleedIntoTheVisibleGlassBackdrop() {
        val pixels = intArrayOf(0x000000ff, 0xffff0000.toInt(), 0x000000ff)
        assertArrayEquals(
            intArrayOf(0x55ff0000, 0x55ff0000, 0x55ff0000),
            blurGlassBackdropPixels(pixels, 3, 1, radius = 1),
        )
    }

    @Test
    fun aSinglePixelAndFullyTransparentWallpapersAreHandledAtClampedEdges() {
        assertArrayEquals(intArrayOf(0xffff8421.toInt()), blurGlassBackdropPixels(intArrayOf(0xffff8421.toInt()), 1, 1))
        assertArrayEquals(IntArray(6), blurGlassBackdropPixels(IntArray(6) { 0x00123456 }, 2, 3))
    }
}
