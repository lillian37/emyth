/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.emyth.app.ui.theme.LocalDarkMode
import kotlin.math.roundToInt

/** One preblurred, downsampled wallpaper shared by every glass surface on a chat page. */
data class GlassBackdrop(
    val imageBitmap: ImageBitmap? = null,
    val backgroundOriginInWindow: Offset = Offset.Unspecified,
    val backgroundSizePx: Size = Size.Zero,
    val baseColor: Color = Color.Transparent,
    val imageAlpha: Float = 1f,
    val gradientTopAlpha: Float = 0f,
    val gradientBottomAlpha: Float = 0f,
    val gradientColor: Color = baseColor,
)

val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/** Glass has a visible tint even when the theme's ordinary surface containers are neutral. */
object ClearGlassDefaults {
    val UserTint = Color(0xFF75BFFF)
    val AssistantTint = Color(0xFFAE8EDB)
    val ThinkingTint = Color(0xFFB0A0D6)
    val InputTint = Color(0xFFB3ABD5)
}

/**
 * Background, tint and the single directional rim share one cached draw node. Foreground text and
 * controls never enter a blur layer. Window position is read only during drawing, not cache building.
 */
@Composable
fun ClearGlassSurface(
    tintColor: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    opacity: Float = 1f,
    dense: Boolean = false,
    backdropEnabled: Boolean = true,
    tintEnabled: Boolean = true,
    shape: Shape? = null,
    onClick: (() -> Unit)? = null,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val glassShape = shape ?: remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val darkTheme = LocalDarkMode.current
    val strength = opacity.coerceIn(0f, 1f)
    val backdrop = LocalGlassBackdrop.current.takeIf { backdropEnabled && strength > 0f }
    val backdropBitmap = backdrop?.imageBitmap
    var originInWindow by remember { mutableStateOf(Offset.Unspecified) }
    val positionModifier = if (backdropBitmap != null) {
        Modifier.onGloballyPositioned { originInWindow = it.positionInWindow() }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(glassShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(positionModifier)
    ) {
        // Custom images stay below the material overlay. Their callers disable the page backdrop.
        background()
        Box(
            modifier = Modifier.matchParentSize().drawWithCache {
                val pageSize = backdrop?.backgroundSizePx ?: Size.Zero
                val pageGradient = if (
                    backdrop != null && pageSize.isSpecified &&
                    pageSize.height.isFinite() && pageSize.height > 0f
                ) {
                    Brush.verticalGradient(
                        colors = listOf(
                            backdrop.gradientColor.copy(alpha = backdrop.gradientTopAlpha.coerceIn(0f, 1f)),
                            backdrop.gradientColor.copy(alpha = backdrop.gradientBottomAlpha.coerceIn(0f, 1f)),
                        ),
                        endY = pageSize.height,
                    )
                } else {
                    null
                }
                val rimWidth = minOf(0.8.dp.toPx(), size.minDimension / 2f)
                val rimInset = minOf(rimWidth / 2f + 0.15.dp.toPx(), size.minDimension / 2f)
                val rimSize = Size(
                    (size.width - rimInset * 2f).coerceAtLeast(0f),
                    (size.height - rimInset * 2f).coerceAtLeast(0f),
                )
                val rimOutline = glassShape.createOutline(rimSize, layoutDirection, this)
                val baseTintStrength = when {
                    darkTheme && dense -> 0.18f
                    darkTheme -> 0.11f
                    dense -> 0.24f
                    else -> 0.18f
                }
                val tintStrength = baseTintStrength * strength * tintColor.alpha
                val readabilityColor = if (darkTheme) Color.Black else Color.White
                val readabilityAlpha = when {
                    darkTheme && dense -> 0.48f
                    darkTheme -> 0.42f
                    dense -> 0.07f
                    else -> 0.025f
                } * strength
                val readability = readabilityColor.copy(alpha = readabilityAlpha)
                // Precompose the constant readability veil into the tint stops: one surface fill.
                val fill = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to tintColor.copy(alpha = tintStrength * 1.12f).compositeOver(readability),
                        0.48f to tintColor.copy(alpha = tintStrength * 0.80f).compositeOver(readability),
                        1f to tintColor.copy(alpha = tintStrength).compositeOver(readability),
                    ),
                )
                val rim = glassRimBrush(rimSize, tintColor, strength, darkTheme)
                val rimStroke = Stroke(width = rimWidth)
                onDrawBehind {
                    if (strength <= 0f) return@onDrawBehind
                    val surfaceOrigin = originInWindow
                    if (
                        backdrop != null && backdropBitmap != null && pageSize.isSpecified &&
                        surfaceOrigin.isSpecified && backdrop.backgroundOriginInWindow.isSpecified
                    ) {
                        val rect = glassBackdropImageRect(
                            imageWidth = backdropBitmap.width,
                            imageHeight = backdropBitmap.height,
                            backgroundWidth = pageSize.width,
                            backgroundHeight = pageSize.height,
                            backgroundX = backdrop.backgroundOriginInWindow.x,
                            backgroundY = backdrop.backgroundOriginInWindow.y,
                            surfaceX = surfaceOrigin.x,
                            surfaceY = surfaceOrigin.y,
                        )
                        if (rect != null) {
                            drawRect(backdrop.baseColor)
                            drawImage(
                                image = backdropBitmap,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(backdropBitmap.width, backdropBitmap.height),
                                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                                dstSize = IntSize(
                                    rect.width.roundToInt().coerceAtLeast(1),
                                    rect.height.roundToInt().coerceAtLeast(1),
                                ),
                                alpha = backdrop.imageAlpha.coerceIn(0f, 1f),
                                filterQuality = FilterQuality.Low,
                            )
                            if (pageGradient != null) {
                                val offsetY = backdrop.backgroundOriginInWindow.y - surfaceOrigin.y
                                val surfaceSize = size
                                translate(top = offsetY) {
                                    drawRect(
                                        brush = pageGradient,
                                        topLeft = Offset(0f, -offsetY),
                                        size = surfaceSize,
                                    )
                                }
                            }
                        }
                    }
                    if (tintEnabled) drawRect(fill)
                    if (rimSize.width > 0f && rimSize.height > 0f) {
                        drawGlassRim(rimOutline, rim, rimStroke, rimInset)
                    }
                }
            }
        )
        content()
    }
}
