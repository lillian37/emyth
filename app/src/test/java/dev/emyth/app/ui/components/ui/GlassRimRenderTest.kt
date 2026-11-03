package dev.emyth.app.ui.components.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual shared Compose drawing on Robolectric's native Canvas; no app or private assets are loaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassRimRenderTest {
    @Test
    fun aRimLeavesTheCenterAndOutsideCornersTransparent() {
        val bitmap = renderRim(360, 170, cornerRadius = 24f)
        try {
            for (y in 60 until 110) {
                for (x in 90 until 270) assertEquals(0, alpha(bitmap.getPixel(x, y)))
            }
            assertEquals(0, alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)))
            assertTrue("The actual native drawing must contain a visible rim", maxAlpha(bitmap) > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fullTransparencyProducesNoRimPixels() {
        val bitmap = renderRim(360, 170, opacity = 0f)
        try {
            assertEquals(0, maxAlpha(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun reflectionIsStrongerOnTheUpperLeftThanTheOppositeEdge() {
        for (darkTheme in listOf(false, true)) {
            val bitmap = renderRim(360, 170, darkTheme = darkTheme)
            try {
                val upperLeft = maxAlpha(bitmap, 0, 0, bitmap.width / 2, bitmap.height / 2)
                val lowerRight = maxAlpha(
                    bitmap, bitmap.width / 2, bitmap.height / 2, bitmap.width, bitmap.height,
                )
                assertTrue("A reflection must be visible", upperLeft > 0)
                assertTrue("The reflection must remain directional", upperLeft > lowerRight)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun aShortPillKeepsItsCenterOpenAndRoundedCornersClear() {
        val bitmap = renderRim(88, 40, cornerRadius = 20f)
        try {
            assertEquals(0, alpha(bitmap.getPixel(44, 20)))
            assertEquals(0, alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, alpha(bitmap.getPixel(87, 0)))
            assertEquals(0, alpha(bitmap.getPixel(0, 39)))
            assertEquals(0, alpha(bitmap.getPixel(87, 39)))
            assertTrue("The short pill must still draw a rim", maxAlpha(bitmap) > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun renderSharedRimSamplesForVisualReview() {
        val output = File(requireNotNull(System.getProperty("emyth.glass.samples.dir")))
        assertTrue(output.isDirectory || output.mkdirs())
        for (darkTheme in listOf(false, true)) {
            val bitmap = renderComparison(darkTheme)
            try {
                val name = if (darkTheme) "glass-rim-dark.png" else "glass-rim-light.png"
                val file = File(output, name)
                FileOutputStream(file).use {
                    assertTrue(
                        "The rendered sample must be encoded",
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it),
                    )
                }
                assertTrue(file.length() > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun renderRim(
        width: Int,
        height: Int,
        cornerRadius: Float = 24f,
        opacity: Float = 1f,
        darkTheme: Boolean = false,
        tintColor: Color = ClearGlassDefaults.AssistantTint,
        previousRim: Boolean = false,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val frameSize = Size(width.toFloat(), height.toFloat())
        val strokeWidth = minOf(0.8f, minOf(width, height) / 2f)
        val inset = strokeWidth / 2f + 0.15f
        val rimSize = Size(width - 2f * inset, height - 2f * inset)
        val density = Density(1f)
        val outline = RoundedCornerShape(cornerRadius.dp).createOutline(rimSize, LayoutDirection.Ltr, density)
        val brush = if (previousRim) {
            previousLinearRim(frameSize, darkTheme)
        } else {
            glassRimBrush(rimSize, tintColor, opacity, darkTheme)
        }
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), frameSize) {
            // The production component calls these same drawing helpers with the same inset geometry.
            drawGlassRim(outline, brush, Stroke(width = strokeWidth), inset)
        }
        return bitmap
    }

    /** The shipped #17 stops, kept only in this comparison fixture, not in production. */
    private fun previousLinearRim(size: Size, darkTheme: Boolean): Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = if (darkTheme) 0.34f else 0.46f),
            0.25f to Color.White.copy(alpha = 0.16f),
            0.60f to Color.White.copy(alpha = 0.025f),
            0.85f to Color.Black.copy(alpha = 0.08f),
            1f to Color.Black.copy(alpha = 0.12f),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )

    private fun renderComparison(darkTheme: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(1040, 790, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(if (darkTheme) 0xff171a26.toInt() else 0xfff1f3f8.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkTheme) AndroidColor.WHITE else 0xff202634.toInt()
            textSize = 22f
            typeface = Typeface.DEFAULT
        }
        canvas.drawText("#17: linear rim", 20f, 34f, text)
        canvas.drawText("New: shared production rim", 540f, 34f, text)
        for (previousRim in listOf(true, false)) {
            val left = if (previousRim) 20f else 540f
            canvas.save()
            canvas.translate(left, 50f)
            drawSyntheticBackdrop(canvas, darkTheme)
            drawSample(canvas, 24, 30, 190, 54, 20f, ClearGlassDefaults.ThinkingTint, darkTheme, previousRim)
            drawSample(canvas, 24, 112, 432, 132, 18f, ClearGlassDefaults.AssistantTint, darkTheme, previousRim)
            drawSample(canvas, 222, 280, 234, 74, 18f, ClearGlassDefaults.UserTint, darkTheme, previousRim)
            drawSample(canvas, 24, 390, 432, 150, 18f, ClearGlassDefaults.AssistantTint, darkTheme, previousRim)
            drawSample(canvas, 12, 586, 456, 100, 26f, ClearGlassDefaults.InputTint, darkTheme, previousRim)
            canvas.restore()
        }
        text.textSize = 16f
        canvas.drawText(
            "Synthetic context only. Rim uses the actual shared Canvas drawing; no AI image generation.",
            20f, 775f, text,
        )
        return bitmap
    }

    private fun drawSample(
        canvas: AndroidCanvas,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Float,
        tint: Color,
        darkTheme: Boolean,
        previousRim: Boolean,
    ) {
        // A constant illustrative tint gives both columns identical context; only the rim differs.
        val veil = (if (darkTheme) Color.Black else Color.White).copy(alpha = if (darkTheme) 0.42f else 0.025f)
        val fill = tint.copy(alpha = if (darkTheme) 0.11f else 0.18f).compositeOver(veil)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
        canvas.drawRoundRect(
            RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()),
            radius, radius, paint,
        )
        val rim = renderRim(width, height, radius, darkTheme = darkTheme, tintColor = tint, previousRim = previousRim)
        try {
            canvas.drawBitmap(rim, x.toFloat(), y.toFloat(), null)
        } finally {
            rim.recycle()
        }
        paint.color = if (darkTheme) AndroidColor.WHITE else 0xff202634.toInt()
        paint.textSize = 17f
        canvas.drawText(if (height < 80) "Glass sample" else "Clear color and a softer edge", x + 16f, y + 30f, paint)
        if (height >= 100) canvas.drawText("Wallpaper detail stays visible.", x + 16f, y + 65f, paint)
    }

    private fun drawSyntheticBackdrop(canvas: AndroidCanvas, darkTheme: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, 710f,
                if (darkTheme) intArrayOf(0xff213556.toInt(), 0xff725b78.toInt(), 0xff263047.toInt())
                else intArrayOf(0xff8bb4e1.toInt(), 0xfff3d6b9.toInt(), 0xffa8b0ca.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, 480f, 710f, paint)
        paint.shader = null
        val random = Random(79)
        repeat(100) {
            paint.color = AndroidColor.argb(random.nextInt(70, 190), 255, 255, 255)
            canvas.drawCircle(random.nextFloat() * 480f, random.nextFloat() * 710f, 0.6f + random.nextFloat(), paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (darkTheme) 0x55202b42 else 0x55536c88
        val branch = Path().apply {
            moveTo(414f, 710f)
            cubicTo(342f, 540f, 402f, 370f, 348f, 192f)
            moveTo(386f, 516f)
            lineTo(319f, 447f)
            moveTo(382f, 389f)
            lineTo(439f, 309f)
        }
        canvas.drawPath(branch, paint)
    }

    private fun alpha(pixel: Int): Int = pixel ushr 24

    private fun maxAlpha(
        bitmap: Bitmap,
        left: Int = 0,
        top: Int = 0,
        right: Int = bitmap.width,
        bottom: Int = bitmap.height,
    ): Int {
        val pixels = IntArray((right - left) * (bottom - top))
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.maxOf { alpha(it) }
    }
}
