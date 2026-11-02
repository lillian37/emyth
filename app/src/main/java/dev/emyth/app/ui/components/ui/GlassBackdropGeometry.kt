/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.ui

import kotlin.math.max

internal data class GlassBackdropImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** ContentScale.Crop, expressed in a surface's local pixels rather than the page's window pixels. */
internal fun glassBackdropImageRect(
    imageWidth: Int,
    imageHeight: Int,
    backgroundWidth: Float,
    backgroundHeight: Float,
    backgroundX: Float,
    backgroundY: Float,
    surfaceX: Float,
    surfaceY: Float,
): GlassBackdropImageRect? {
    if (imageWidth <= 0 || imageHeight <= 0 || backgroundWidth <= 0f || backgroundHeight <= 0f) return null
    if (!backgroundWidth.isFinite() || !backgroundHeight.isFinite() ||
        !backgroundX.isFinite() || !backgroundY.isFinite() || !surfaceX.isFinite() || !surfaceY.isFinite()
    ) return null

    val scale = max(backgroundWidth / imageWidth, backgroundHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return GlassBackdropImageRect(
        left = backgroundX - surfaceX + (backgroundWidth - width) / 2f,
        top = backgroundY - surfaceY + (backgroundHeight - height) / 2f,
        width = width,
        height = height,
    )
}
