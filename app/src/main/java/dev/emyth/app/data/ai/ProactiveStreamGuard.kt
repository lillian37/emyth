/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A bounded background request failed; this is not a user cancellation. */
class ProactiveStreamTimeoutException(val phase: Phase) : IOException(
    when (phase) {
        Phase.FIRST_PROGRESS -> "后台生成等待模型输出超时，已结束此次请求。"
        Phase.IDLE -> "后台生成长时间没有收到新的输出，已结束此次请求。"
        Phase.TOTAL -> "后台生成已超过最长等待时间，已结束此次请求。"
    }
) {
    enum class Phase { FIRST_PROGRESS, IDLE, TOTAL }
}

private sealed interface ProgressWaitResult<out T> {
    data class Progress<T>(val value: T) : ProgressWaitResult<T>
    data object Closed : ProgressWaitResult<Nothing>
}

/**
 * Bounds one proactive model stream, not the surrounding tool loop. Every value is forwarded in
 * order, but heartbeat/empty values do not extend the deadline for useful model output. Delivering
 * a useful chunk is outside the first/idle wait budget; the total budget still bounds all collection
 * work. Coroutine timers also close callbackFlow transports through awaitClose on cancellation.
 */
fun <T> Flow<T>.guardProactiveStream(
    firstProgressTimeout: Duration = 15.minutes,
    idleTimeout: Duration = 10.minutes,
    totalTimeout: Duration = 30.minutes,
    isProgress: (T) -> Boolean = { true },
): Flow<T> = flow {
    require(firstProgressTimeout > Duration.ZERO && firstProgressTimeout.isFinite())
    require(idleTimeout > Duration.ZERO && idleTimeout.isFinite())
    require(totalTimeout > Duration.ZERO && totalTimeout.isFinite())

    coroutineScope {
        val values = Channel<T>(Channel.RENDEZVOUS)
        val producer = launch {
            try {
                this@guardProactiveStream.collect { values.send(it) }
                values.close()
            } catch (cancelled: CancellationException) {
                values.close(cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                values.close(failure)
            }
        }
        try {
            suspend fun receiveProgress(): ProgressWaitResult<T> {
                while (true) {
                    val next = values.receiveCatching()
                    if (next.isClosed) {
                        next.exceptionOrNull()?.let { throw it }
                        return ProgressWaitResult.Closed
                    }
                    val value = next.getOrThrow()
                    if (isProgress(value)) return ProgressWaitResult.Progress(value)
                    // Forward heartbeat values without restarting the current useful-output timer.
                    emit(value)
                }
            }
            val completed = withTimeoutOrNull(totalTimeout) total@{
                var hasProgress = false
                while (true) {
                    val waitDuration = if (hasProgress) idleTimeout else firstProgressTimeout
                    val result = withTimeoutOrNull(waitDuration) { receiveProgress() }
                        ?: throw ProactiveStreamTimeoutException(
                            if (hasProgress) ProactiveStreamTimeoutException.Phase.IDLE
                            else ProactiveStreamTimeoutException.Phase.FIRST_PROGRESS
                        )
                    when (result) {
                        ProgressWaitResult.Closed -> return@total true
                        is ProgressWaitResult.Progress -> {
                            hasProgress = true
                            emit(result.value)
                        }
                    }
                }
            }
            if (completed == null) {
                throw ProactiveStreamTimeoutException(ProactiveStreamTimeoutException.Phase.TOTAL)
            }
        } finally {
            values.cancel()
            withContext(NonCancellable) { producer.cancelAndJoin() }
        }
    }
}
