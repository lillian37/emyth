package me.rerere.ai.util

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageEncodeCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun cacheHitsSkipEncodingAndPersistAcrossCacheInstances() {
        val directory = temporary.newFolder("cache")
        val source = source("source.jpg")
        val encoded = jpegBytes(7)
        val cache = EncodedImageDiskCache(directory)
        val calls = AtomicInteger()

        assertArrayEquals(encoded, encode(cache, source) { calls.incrementAndGet(); encoded })
        assertArrayEquals(encoded, encode(cache, source) { error("A cache hit must not encode the source") })
        assertArrayEquals(encoded, encode(EncodedImageDiskCache(directory), source) {
            error("A new cache instance must reuse the completed disk entry")
        })
        assertEquals(1, calls.get())
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun everyEncodingParameterParticipatesInCacheIdentity() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val source = source("source.jpg")
        val calls = AtomicInteger()
        val requests = listOf(
            Triple(10_000, 16_000_000L, 85),
            Triple(5_000, 16_000_000L, 85),
            Triple(10_000, 8_000_000L, 85),
            Triple(10_000, 16_000_000L, 90),
        )

        requests.forEachIndexed { index, (dimension, pixels, quality) ->
            val expected = jpegBytes(index)
            assertArrayEquals(expected, cache.getOrEncode(source, dimension, pixels, quality) {
                calls.incrementAndGet()
                expected
            })
            assertArrayEquals(expected, cache.getOrEncode(source, dimension, pixels, quality) {
                error("Identical encoding parameters must hit their own cache entry")
            })
        }

        assertEquals(requests.size, calls.get())
    }

    @Test
    fun changingTheSourceTimestampInvalidatesAnEqualLengthFile() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val source = source("source.jpg")
        val originalTimestamp = source.lastModified()
        encode(cache, source) { jpegBytes(1) }

        source.writeBytes(byteArrayOf(7, 8, 9))
        assertTrue(source.setLastModified(originalTimestamp + 2_000))
        val replacement = jpegBytes(2)
        val calls = AtomicInteger()
        assertArrayEquals(replacement, encode(cache, source) { calls.incrementAndGet(); replacement })
        assertArrayEquals(replacement, encode(cache, source) { error("The replacement must now be cached") })
        assertEquals(1, calls.get())
    }

    @Test
    fun changingTheSourceLengthInvalidatesEvenWhenItsTimestampIsPreserved() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val source = source("source.jpg")
        val originalTimestamp = source.lastModified()
        encode(cache, source) { jpegBytes(1) }

        source.appendBytes(byteArrayOf(4))
        assertTrue(source.setLastModified(originalTimestamp))
        val replacement = jpegBytes(2)
        val calls = AtomicInteger()
        assertArrayEquals(replacement, encode(cache, source) { calls.incrementAndGet(); replacement })
        assertEquals(1, calls.get())
    }

    @Test
    fun differentSourcePathsDoNotShareAnEntryWithIdenticalMetadata() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val first = source("first.jpg")
        val second = source("second.jpg")
        assertTrue(second.setLastModified(first.lastModified()))
        val calls = AtomicInteger()

        assertArrayEquals(jpegBytes(1), encode(cache, first) { calls.incrementAndGet(); jpegBytes(1) })
        assertArrayEquals(jpegBytes(2), encode(cache, second) { calls.incrementAndGet(); jpegBytes(2) })
        assertArrayEquals(jpegBytes(1), encode(cache, first) { error("The first source must keep its own entry") })
        assertEquals(2, calls.get())
    }

    @Test
    fun aSourceChangedDuringEncodingIsNotPublishedUnderItsOldIdentity() {
        val directory = temporary.newFolder("cache")
        val cache = EncodedImageDiskCache(directory)
        val source = source("source.jpg")
        val first = jpegBytes(1)
        val replacement = jpegBytes(2)
        val calls = AtomicInteger()

        assertArrayEquals(first, encode(cache, source) {
            calls.incrementAndGet()
            source.appendBytes(byteArrayOf(4))
            first
        })
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "entry" })
        assertArrayEquals(replacement, encode(cache, source) { calls.incrementAndGet(); replacement })
        assertArrayEquals(replacement, encode(cache, source) { error("Only the stable result should be cached") })
        assertEquals(2, calls.get())
    }

    @Test
    fun anEvictedCacheFileFallsBackToEncodingAndCanBeCachedAgain() {
        val directory = temporary.newFolder("cache")
        val cache = EncodedImageDiskCache(directory)
        val source = source("source.jpg")
        encode(cache, source) { jpegBytes(1) }
        assertTrue(directory.listFiles().orEmpty().single { it.extension == "entry" }.delete())
        val replacement = jpegBytes(2)
        val calls = AtomicInteger()

        assertArrayEquals(replacement, encode(cache, source) { calls.incrementAndGet(); replacement })
        assertArrayEquals(replacement, encode(cache, source) { error("A recovered entry should hit") })
        assertEquals(1, calls.get())
    }

    @Test
    fun emptyTruncatedAndCorruptedCacheEntriesFallBackInsteadOfReturningBadBytes() {
        val directory = temporary.newFolder("cache")
        val cache = EncodedImageDiskCache(directory)
        val source = source("source.jpg")
        encode(cache, source) { jpegBytes(1) }
        val corruptions: List<(ByteArray) -> ByteArray> = listOf(
            { byteArrayOf() },
            { bytes -> bytes.copyOf(bytes.size - 1) },
            { bytes -> bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
        )
        val calls = AtomicInteger()

        corruptions.forEachIndexed { index, corrupt ->
            val entry = directory.listFiles().orEmpty().single { it.extension == "entry" }
            entry.writeBytes(corrupt(entry.readBytes()))
            val replacement = jpegBytes(index + 2)
            assertArrayEquals(replacement, encode(cache, source) { calls.incrementAndGet(); replacement })
            assertArrayEquals(replacement, encode(cache, source) { error("The damaged entry must be replaced") })
        }

        assertEquals(corruptions.size, calls.get())
    }

    @Test
    fun anUnavailableCacheDirectoryNeverPreventsOriginalEncoding() {
        val cache = EncodedImageDiskCache(temporary.newFile("not-a-directory"))
        val source = source("source.jpg")
        val calls = AtomicInteger()

        assertArrayEquals(jpegBytes(1), encode(cache, source) { calls.incrementAndGet(); jpegBytes(1) })
        assertArrayEquals(jpegBytes(2), encode(cache, source) { calls.incrementAndGet(); jpegBytes(2) })
        assertEquals(2, calls.get())
    }

    @Test
    fun encodingFailuresPropagateAndDoNotPoisonLaterSuccessfulRequests() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val source = source("source.jpg")
        val failure = IOException("original encoder failed")

        try {
            encode(cache, source) { throw failure }
            fail("The original encoder failure must propagate")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }

        val expected = jpegBytes(3)
        assertArrayEquals(expected, encode(cache, source) { expected })
        assertArrayEquals(expected, encode(cache, source) { error("The later success must be cached") })
    }

    @Test
    fun missingSourcesStillUseTheOriginalEncoderWithoutCachingItsResult() {
        val directory = temporary.newFolder("cache")
        val cache = EncodedImageDiskCache(directory)
        val source = File(temporary.root, "missing.jpg")
        val calls = AtomicInteger()

        assertArrayEquals(jpegBytes(1), encode(cache, source) { calls.incrementAndGet(); jpegBytes(1) })
        assertArrayEquals(jpegBytes(2), encode(cache, source) { calls.incrementAndGet(); jpegBytes(2) })
        assertEquals(2, calls.get())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "entry" })
    }

    @Test
    fun evictionKeepsRecentlyReadEntriesAndBoundsTheDiskTotal() {
        val directory = temporary.newFolder("cache")
        val clock = AtomicLong(1_700_000_000_000L)
        val limit = 150L
        val cache = EncodedImageDiskCache(directory, limit) { clock.addAndGet(1_000) }
        val first = source("first.jpg")
        val second = source("second.jpg")
        val third = source("third.jpg")
        val firstBytes = jpegBytes(1, 24)
        encode(cache, first) { firstBytes }
        encode(cache, second) { jpegBytes(2, 24) }
        assertArrayEquals(firstBytes, encode(cache, first) { error("Reading must refresh recency") })

        encode(cache, third) { jpegBytes(3, 24) }

        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= limit)
        assertArrayEquals(firstBytes, encode(cache, first) { error("The recent first entry must survive") })
        val calls = AtomicInteger()
        val replacement = jpegBytes(4, 24)
        assertArrayEquals(replacement, encode(cache, second) { calls.incrementAndGet(); replacement })
        assertEquals(1, calls.get())
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= limit)
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
    }

    @Test
    fun anOversizedEncodedImageIsReturnedWithoutExceedingTheCacheBudget() {
        val directory = temporary.newFolder("cache")
        val limit = 100L
        val cache = EncodedImageDiskCache(directory, limit)
        val source = source("source.jpg")
        val expected = jpegBytes(1, 256)
        val calls = AtomicInteger()

        repeat(2) {
            assertArrayEquals(expected, encode(cache, source) { calls.incrementAndGet(); expected })
        }

        assertEquals(2, calls.get())
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= limit)
    }

    @Test
    fun concurrentRequestsForTheSameImageEncodeOnlyOnceAndReturnCompleteBytes() {
        val directory = temporary.newFolder("cache")
        val cache = EncodedImageDiskCache(directory)
        val source = source("source.jpg")
        val expected = jpegBytes(5, 8_192)
        val calls = AtomicInteger()
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val encoding = CountDownLatch(1)
        val releaseEncoder = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)

        try {
            val futures = (0 until 6).map {
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    encode(cache, source) {
                        calls.incrementAndGet()
                        encoding.countDown()
                        check(releaseEncoder.await(5, TimeUnit.SECONDS))
                        expected
                    }
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(encoding.await(5, TimeUnit.SECONDS))
            releaseEncoder.countDown()

            futures.forEach { assertArrayEquals(expected, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "part" })
        } finally {
            start.countDown()
            releaseEncoder.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentRequestsWithDifferentParametersNeverShareEncodedOutput() {
        val cache = EncodedImageDiskCache(temporary.newFolder("cache"))
        val source = source("source.jpg")
        val calls = AtomicInteger()
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)

        try {
            val requests = (0 until 6).map { index ->
                val quality = if (index % 2 == 0) 80 else 90
                quality to executor.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    cache.getOrEncode(source, 10_000, 16_000_000L, quality) {
                        calls.incrementAndGet()
                        jpegBytes(quality)
                    }
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            requests.forEach { (quality, future) ->
                assertArrayEquals(jpegBytes(quality), future.get(5, TimeUnit.SECONDS))
            }
            assertEquals(2, calls.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun source(name: String): File = temporary.newFile(name).apply {
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun jpegBytes(marker: Int, size: Int = 64): ByteArray = ByteArray(size) { marker.toByte() }.apply {
        this[0] = 0xff.toByte()
        this[1] = 0xd8.toByte()
        this[lastIndex - 1] = 0xff.toByte()
        this[lastIndex] = 0xd9.toByte()
    }

    private fun encode(cache: EncodedImageDiskCache, source: File, encode: () -> ByteArray): ByteArray =
        cache.getOrEncode(source, 10_000, 16_000_000L, 85, encode)
}
