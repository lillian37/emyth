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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
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

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Glass has a visible tint even when the theme's ordinary surface containers are neutral. */
object ClearGlassDefaults {
    val UserTint = Color(0xFF75BFFF)
    val AssistantTint = Color(0xFFAE8EDB)
    val ThinkingTint = Color(0xFFB0A0D6)
    val InputTint = Color(0xFFB3ABD5)
}

/**
 * A lightly tinted surface. Only its wallpaper layer is softened; foreground text and controls never
 * enter an offscreen blur layer. Position is read during drawing so scrolling does not recompose text.
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
            .shadow(
                elevation = if (strength > 0f) 1.5.dp else 0.dp,
                shape = glassShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f * strength),
                spotColor = Color.Black.copy(alpha = 0.14f * strength),
            )
            .clip(glassShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(positionModifier)
    ) {
        if (backdrop != null && backdropBitmap != null) {
            Box(
                modifier = Modifier.matchParentSize().drawWithCache {
                    val bitmap = backdropBitmap
                    val pageSize = backdrop.backgroundSizePx
                    val gradient = if (pageSize.isSpecified && pageSize.height.isFinite() && pageSize.height > 0f) {
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
                    onDrawBehind {
                        val surfaceOrigin = originInWindow
                        if (!pageSize.isSpecified || !surfaceOrigin.isSpecified ||
                            !backdrop.backgroundOriginInWindow.isSpecified
                        ) return@onDrawBehind
                        val rect = glassBackdropImageRect(
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
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
                                image = bitmap,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(bitmap.width, bitmap.height),
                                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                                dstSize = IntSize(
                                    rect.width.roundToInt().coerceAtLeast(1),
                                    rect.height.roundToInt().coerceAtLeast(1),
                                ),
                                alpha = backdrop.imageAlpha.coerceIn(0f, 1f),
                                filterQuality = FilterQuality.Low,
                            )
                            if (gradient != null) {
                                val offsetY = backdrop.backgroundOriginInWindow.y - surfaceOrigin.y
                                val surfaceSize = size
                                translate(top = offsetY) {
                                    drawRect(
                                        brush = gradient,
                                        topLeft = Offset(0f, -offsetY),
                                        size = surfaceSize,
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
        background()
        Box(
            modifier = Modifier.matchParentSize().drawWithCache {
                // Keep the whole stroke inside the outer clip, including its antialiased edge.
                val rimWidth = minOf(1.dp.toPx(), size.minDimension / 2f)
                val rimInset = minOf(rimWidth / 2f + 0.15.dp.toPx(), size.minDimension / 2f)
                val rimSize = Size(
                    (size.width - rimInset * 2f).coerceAtLeast(0f),
                    (size.height - rimInset * 2f).coerceAtLeast(0f),
                )
                val rimOutline = glassShape.createOutline(rimSize, layoutDirection, this)
                val bevelWidth = minOf(2.dp.toPx(), size.minDimension / 2f)
                val bevelInset = minOf(bevelWidth / 2f + 0.15.dp.toPx(), size.minDimension / 2f)
                val bevelOutline = glassShape.createOutline(
                    Size(
                        (size.width - bevelInset * 2f).coerceAtLeast(0f),
                        (size.height - bevelInset * 2f).coerceAtLeast(0f),
                    ),
                    layoutDirection,
                    this,
                )
                val baseTintStrength = when {
                    darkTheme && dense -> 0.18f
                    darkTheme -> 0.11f
                    dense -> 0.24f
                    else -> 0.18f
                }
                val tintStrength = baseTintStrength * strength * tintColor.alpha
                val fill = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to tintColor.copy(alpha = tintStrength * 1.12f),
                        0.48f to tintColor.copy(alpha = tintStrength * 0.80f),
                        1f to tintColor.copy(alpha = tintStrength),
                    ),
                )
                val readabilityColor = if (darkTheme) Color.Black else Color.White
                val readabilityAlpha = when {
                    darkTheme && dense -> 0.48f
                    darkTheme -> 0.42f
                    dense -> 0.07f
                    else -> 0.025f
                } * strength
                val rimBase = Color.White.copy(alpha = (if (darkTheme) 0.14f else 0.22f) * strength)
                val rimHighlight = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = (if (darkTheme) 0.46f else 0.68f) * strength),
                        0.30f to Color.White.copy(alpha = 0.32f * strength),
                        0.66f to Color.White.copy(alpha = 0.04f * strength),
                        1f to Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val lowerEdge = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.16f * strength),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val bevel = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to tintColor.copy(alpha = 0.18f * strength * tintColor.alpha),
                        0.48f to tintColor.copy(alpha = 0.07f * strength * tintColor.alpha),
                        1f to tintColor.copy(alpha = 0.24f * strength * tintColor.alpha),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val rimStroke = Stroke(width = rimWidth)
                val bevelStroke = Stroke(width = bevelWidth)
                onDrawBehind {
                    if (strength <= 0f || rimSize.width <= 0f || rimSize.height <= 0f) return@onDrawBehind
                    if (tintEnabled) {
                        drawRect(readabilityColor.copy(alpha = readabilityAlpha))
                        drawRect(fill)
                        translate(left = bevelInset, top = bevelInset) {
                            drawOutline(bevelOutline, brush = bevel, style = bevelStroke)
                        }
                    }
                    translate(left = rimInset, top = rimInset) {
                        drawOutline(rimOutline, color = rimBase, style = rimStroke)
                        drawOutline(rimOutline, brush = lowerEdge, style = rimStroke)
                        drawOutline(rimOutline, brush = rimHighlight, style = rimStroke)
                    }
                }
            }
        )
        content()
    }
}
