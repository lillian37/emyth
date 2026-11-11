package me.rerere.ai.util

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Best-effort JPEG cache for repeated model requests; original GIFs bypass it in FileEncoder. */
object ImageEncodeCache {
    @Volatile
    private var cache: EncodedImageDiskCache? = null

    @Synchronized
    fun init(cacheDir: File) {
        val directory = cacheDir.resolve("encoded_images").absoluteFile
        if (cache?.directory != directory) cache = EncodedImageDiskCache(directory)
    }

    internal fun getOrEncode(
        source: File,
        maxDimension: Int,
        maxPixels: Long,
        quality: Int,
        encode: () -> ByteArray,
    ): ByteArray = cache?.getOrEncode(source, maxDimension, maxPixels, quality, encode) ?: encode()
}

/**
 * Android-independent storage. A small integrity header detects truncated or damaged entries without decoding JPEGs.
 * Encoding happens outside the disk lock; fixed source locks coalesce concurrent requests without an unbounded map.
 */
internal class EncodedImageDiskCache(
    internal val directory: File,
    private val maxTotalBytes: Long = 256L * 1024 * 1024,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val diskLock = Any()
    private val sourceLocks = Array(16) { Any() }

    init {
        require(maxTotalBytes >= 0)
        try {
            if (directory.isDirectory) trim()
        } catch (_: Exception) {
            // Stale cache files must not make provider initialization fail.
        }
    }

    fun getOrEncode(
        source: File,
        maxDimension: Int,
        maxPixels: Long,
        quality: Int,
        encode: () -> ByteArray,
    ): ByteArray {
        val sourceLock = sourceLocks[(source.absolutePath.hashCode() and Int.MAX_VALUE) % sourceLocks.size]
        synchronized(sourceLock) {
            val before = snapshot(source) ?: return encode()
            val key = hash("$VERSION|${before.path}|${before.modified}|${before.size}|" +
                "$maxDimension|$maxPixels|$quality")
            read(key)?.let { bytes ->
                if (snapshot(source) == before) return bytes
            }
            // Exceptions from the actual encoder must still reach the caller. Only cache IO is optional.
            val bytes = encode()
            if (snapshot(source) == before) write(key, bytes)
            return bytes
        }
    }

    private fun snapshot(source: File): SourceSnapshot? = try {
        if (source.isFile) SourceSnapshot(source.canonicalPath, source.lastModified(), source.length()) else null
    } catch (_: Exception) {
        null
    }

    private fun read(key: String): ByteArray? = synchronized(diskLock) {
        val target = directory.resolve("$key.entry")
        try {
            if (!target.isFile) return@synchronized null
            val bytes = DataInputStream(target.inputStream().buffered()).use { input ->
                check(input.readInt() == MAGIC)
                val size = input.readInt()
                check(size > 0 && size.toLong() + HEADER_BYTES <= maxTotalBytes)
                check(target.length() == size.toLong() + HEADER_BYTES)
                val expectedDigest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val payload = ByteArray(size).also(input::readFully)
                check(MessageDigest.isEqual(expectedDigest, digest(payload)))
                payload
            }
            target.setLastModified(nowMillis())
            bytes
        } catch (_: Exception) {
            // Includes an entry removed by Android's cache cleaner between its existence check and opening it.
            runCatching { target.delete() }
            null
        }
    }

    private fun write(key: String, bytes: ByteArray) = synchronized(diskLock) {
        if (bytes.isEmpty() || bytes.size.toLong() + HEADER_BYTES > maxTotalBytes) return@synchronized
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            temporary = File.createTempFile("encoded_", ".part", directory)
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(bytes.size)
                output.write(digest(bytes))
                output.write(bytes)
            }
            // Both paths are in the same directory. If atomic replacement is unsupported, skip caching.
            val target = directory.resolve("$key.entry")
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            target.setLastModified(nowMillis())
            trim()
        } catch (_: Exception) {
            // Cache permissions, full storage and atomic-move failures must never prevent sending the image.
        } finally {
            temporary?.let { runCatching { it.delete() } }
        }
    }

    private fun trim() {
        val files = directory.listFiles() ?: return
        files.filter { it.isFile && it.extension == "part" }.forEach { it.delete() }
        val entries = files.filter { it.isFile && it.extension == "entry" }
        var total = entries.sumOf(File::length)
        for (entry in entries.sortedBy(File::lastModified)) {
            if (total <= maxTotalBytes) break
            val size = entry.length()
            if (entry.delete()) total -= size
        }
    }

    private data class SourceSnapshot(val path: String, val modified: Long, val size: Long)

    private companion object {
        // Bump when compression semantics change, including EXIF normalization or output format.
        const val VERSION = 1
        const val MAGIC = 0x45494D47
        const val DIGEST_BYTES = 32
        const val HEADER_BYTES = 8L + DIGEST_BYTES

        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun hash(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
