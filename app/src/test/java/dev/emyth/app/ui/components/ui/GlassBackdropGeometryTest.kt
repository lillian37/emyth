package dev.emyth.app.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassBackdropGeometryTest {
    @Test
    fun portraitImageMatchesPageCoordinatesEvenWhenSurfaceScrolls() {
        val initial = glassBackdropImageRect(300, 600, 600f, 1200f, 0f, 48f, 24f, 248f)!!
        val scrolled = glassBackdropImageRect(300, 600, 600f, 1200f, 0f, 48f, 24f, 148f)!!
        assertEquals(-24f, initial.left, 0.001f)
        assertEquals(-200f, initial.top, 0.001f)
        assertEquals(600f, initial.width, 0.001f)
        assertEquals(1200f, initial.height, 0.001f)
        assertEquals(initial.top + 100f, scrolled.top, 0.001f)
    }

    @Test
    fun landscapeWallpaperIsCenterCroppedIntoPortraitPage() {
        val rect = glassBackdropImageRect(1200, 600, 600f, 1200f, 0f, 0f, 20f, 300f)!!
        assertEquals(2400f, rect.width, 0.001f)
        assertEquals(1200f, rect.height, 0.001f)
        assertEquals(-920f, rect.left, 0.001f)
        assertEquals(-300f, rect.top, 0.001f)
    }

    @Test
    fun imageAndSurfaceMoveTogetherWhenPageWindowOriginChanges() {
        val first = glassBackdropImageRect(640, 640, 400f, 800f, 0f, 0f, 24f, 300f)
        val moved = glassBackdropImageRect(640, 640, 400f, 800f, 80f, 100f, 104f, 400f)
        assertEquals(first, moved)
    }

    @Test
    fun layoutBeforeMeasurementOrUnspecifiedPositionsDoesNotProduceInvalidDrawBounds() {
        assertNull(glassBackdropImageRect(640, 640, 0f, 800f, 0f, 0f, 0f, 0f))
        assertNull(glassBackdropImageRect(0, 640, 400f, 800f, 0f, 0f, 0f, 0f))
        assertNull(glassBackdropImageRect(640, 640, 400f, 800f, Float.NaN, 0f, 0f, 0f))
        assertNull(glassBackdropImageRect(640, 640, 400f, Float.POSITIVE_INFINITY, 0f, 0f, 0f, 0f))
    }
}
