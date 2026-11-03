/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.atan2

/** A stationary light cue, cached by the caller; it adds no shadow or extra drawing pass. */
internal fun glassRimBrush(size: Size, tintColor: Color, opacity: Float, darkTheme: Boolean): Brush {
    val width = if (size.isSpecified && size.width.isFinite() && size.width > 0f) size.width else 1f
    val height = if (size.isSpecified && size.height.isFinite() && size.height > 0f) size.height else 1f
    val strength = opacity.coerceIn(0f, 1f)
    // Place the light on the upper-left curve even for very wide, shallow chat bubbles.
    val cornerAngle = (atan2(height.toDouble(), width.toDouble()) / (2.0 * PI)).toFloat()
        .coerceIn(0.02f, 0.23f)
    val pearl = lerp(Color.White, tintColor.copy(alpha = 1f), 0.06f)
    val peak = if (darkTheme) 0.30f else 0.38f
    return Brush.sweepGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            cornerAngle to Color.Black.copy(alpha = 0.05f * strength),
            0.25f to Color.Black.copy(alpha = 0.025f * strength),
            0.375f to Color.Transparent,
            0.50f to pearl.copy(alpha = 0.10f * strength),
            (0.50f + cornerAngle) to pearl.copy(alpha = peak * strength),
            0.75f to pearl.copy(alpha = 0.14f * strength),
            0.875f to pearl.copy(alpha = 0.025f * strength),
            1f to Color.Transparent,
        ),
        center = Offset(width / 2f, height / 2f),
    )
}

/** Shared verbatim by the screen and the native-graphics render fixture. */
internal fun DrawScope.drawGlassRim(outline: Outline, brush: Brush, stroke: Stroke, inset: Float) {
    translate(left = inset, top = inset) {
        drawOutline(outline, brush = brush, style = stroke)
    }
}
