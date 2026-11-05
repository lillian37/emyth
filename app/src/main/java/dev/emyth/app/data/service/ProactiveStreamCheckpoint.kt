/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * Rate-limits durable checkpoints of already merged snapshots, never individual model deltas.
 * An ordinary upstream failure is delayed until its latest snapshot has been delivered. Cancellation
 * and downstream persistence failures still stop immediately; they must not cause later writes.
 */
fun <T> Flow<T>.checkpointProactiveSnapshots(intervalMillis: Long = 500L): Flow<T> = flow {
    require(intervalMillis > 0L)
    var upstreamFailure: Throwable? = null
    this@checkpointProactiveSnapshots
        .catch { failure ->
            if (failure is CancellationException) throw failure
            upstreamFailure = failure
        }
        .conflate()
        .collect { snapshot ->
            emit(snapshot)
            delay(intervalMillis)
        }
    upstreamFailure?.let { throw it }
}
