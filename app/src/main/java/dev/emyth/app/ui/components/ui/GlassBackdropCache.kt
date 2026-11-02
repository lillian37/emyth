/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.ui.components.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GLASS_BACKDROP_MAX_DIMENSION = 640
private const val GLASS_BACKDROP_CACHE_ENTRIES = 2
private val glassBackdropMutex = Mutex()
private val glassBackdropCache = LinkedHashMap<String, ImageBitmap>(3, 0.75f, true)

/**
 * Loads and gently softens a static wallpaper only while the existing blur switches are enabled.
 * All bubbles share this small bitmap; scrolling only changes which part they draw.
 */
@Composable
fun rememberGlassBackdropBitmap(model: String?, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    // Key the state as well as the effect so a new wallpaper never flashes the old cached image.
    var bitmap by remember(model, enabled) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(model, enabled) {
        if (!enabled || model.isNullOrBlank()) return@LaunchedEffect
        bitmap = try {
            loadGlassBackdropBitmap(context, model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unsupported/missing images keep the transparent tint and rim without a backdrop layer.
            null
        }
    }
    return bitmap
}

private suspend fun loadGlassBackdropBitmap(context: Context, model: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        glassBackdropMutex.withLock {
            val key = glassBackdropCacheKey(model)
            glassBackdropCache[key]?.let { return@withLock it }
            val request = ImageRequest.Builder(context)
                .data(model)
                .size(GLASS_BACKDROP_MAX_DIMENSION, GLASS_BACKDROP_MAX_DIMENSION)
                .scale(Scale.FIT)
                .precision(Precision.EXACT)
                .allowHardware(false)
                .memoryCacheKey("emyth-clear-glass:$key")
                .build()
            val image = SingletonImageLoader.get(context).execute(request).image as? BitmapImage
                ?: return@withLock null
            val source = image.bitmap
            val (width, height) = boundedGlassBackdropSize(
                source.width,
                source.height,
                GLASS_BACKDROP_MAX_DIMENSION,
            )
            val sampled = if (source.width != width || source.height != height) {
                Bitmap.createScaledBitmap(source, width, height, true)
            } else {
                source
            }
            val pixels = IntArray(width * height)
            sampled.getPixels(pixels, 0, width, 0, 0, width, height)
            // Never recycle Coil-owned images. Only our temporary scaled bitmap belongs to us.
            if (sampled !== source) sampled.recycle()
            currentCoroutineContext().ensureActive()
            val softened = blurGlassBackdropPixels(pixels, width, height, radius = 1)
            currentCoroutineContext().ensureActive()
            val bitmap = Bitmap.createBitmap(softened, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
            glassBackdropCache[key] = bitmap
            while (glassBackdropCache.size > GLASS_BACKDROP_CACHE_ENTRIES) {
                val oldest = glassBackdropCache.entries.iterator()
                oldest.next()
                // A composable may still draw an evicted bitmap, so eviction must not recycle it.
                oldest.remove()
            }
            bitmap
        }
    }

private fun glassBackdropCacheKey(model: String): String {
    val uri = Uri.parse(model)
    val file = when {
        model.startsWith("/") -> File(model)
        uri.scheme == "file" -> uri.path?.let(::File)
        uri.scheme == null -> File(model)
        else -> null
    }
    return if (file != null) "$model#${file.lastModified()}:${file.length()}" else model
}
