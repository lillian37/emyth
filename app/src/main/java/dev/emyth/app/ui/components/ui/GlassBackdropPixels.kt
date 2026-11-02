/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.ui

import kotlin.math.max
import kotlin.math.roundToInt

/** Keep the shared wallpaper small even when a loader returns a larger cached bitmap. */
internal fun boundedGlassBackdropSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxDimension > 0)
    if (max(width, height) <= maxDimension) return width to height
    val scale = maxDimension.toDouble() / max(width, height)
    return (width * scale).roundToInt().coerceIn(1, maxDimension) to
        (height * scale).roundToInt().coerceIn(1, maxDimension)
}

/**
 * A small separable box blur, run once per wallpaper rather than once per visible bubble.
 * RGB is weighted by alpha so invisible pixels cannot bleed color into transparent image edges.
 */
internal fun blurGlassBackdropPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int = 2,
): IntArray {
    require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
    require(radius in 0..8)
    if (radius == 0) return pixels.copyOf()
    val intermediate = IntArray(pixels.size)
    val result = IntArray(pixels.size)
    blurGlassBackdropPass(pixels, intermediate, width, height, radius, horizontal = true)
    blurGlassBackdropPass(intermediate, result, width, height, radius, horizontal = false)
    return result
}

private fun blurGlassBackdropPass(
    source: IntArray,
    target: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val lineLength = if (horizontal) width else height
    val lineCount = if (horizontal) height else width
    val stride = if (horizontal) 1 else width
    val sampleCount = radius * 2 + 1
    for (line in 0 until lineCount) {
        val start = if (horizontal) line * width else line
        var alpha = 0
        var redAlpha = 0
        var greenAlpha = 0
        var blueAlpha = 0
        for (offset in -radius..radius) {
            val pixel = source[start + offset.coerceIn(0, lineLength - 1) * stride]
            val a = pixel ushr 24
            alpha += a
            redAlpha += ((pixel ushr 16) and 0xff) * a
            greenAlpha += ((pixel ushr 8) and 0xff) * a
            blueAlpha += (pixel and 0xff) * a
        }
        for (position in 0 until lineLength) {
            target[start + position * stride] = if (alpha == 0) {
                0
            } else {
                val a = (alpha + sampleCount / 2) / sampleCount
                val r = (redAlpha + alpha / 2) / alpha
                val g = (greenAlpha + alpha / 2) / alpha
                val b = (blueAlpha + alpha / 2) / alpha
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val leaving = source[start + (position - radius).coerceIn(0, lineLength - 1) * stride]
            val entering = source[start + (position + radius + 1).coerceIn(0, lineLength - 1) * stride]
            val leavingAlpha = leaving ushr 24
            val enteringAlpha = entering ushr 24
            alpha += enteringAlpha - leavingAlpha
            redAlpha += ((entering ushr 16) and 0xff) * enteringAlpha -
                ((leaving ushr 16) and 0xff) * leavingAlpha
            greenAlpha += ((entering ushr 8) and 0xff) * enteringAlpha -
                ((leaving ushr 8) and 0xff) * leavingAlpha
            blueAlpha += (entering and 0xff) * enteringAlpha - (leaving and 0xff) * leavingAlpha
        }
    }
}
