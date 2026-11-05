/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import java.io.IOException
import kotlinx.coroutines.withTimeoutOrNull

internal class ProactivePreparationTimeoutException(step: String) : IOException("后台唤醒准备超时：$step")

private data class PreparedValue<T>(val value: T)

/** Bound preparation before the model stream starts; preserve caller cancellation and nullable results. */
internal suspend fun <T> prepareProactiveStep(
    step: String,
    timeoutMillis: Long = 60_000L,
    block: suspend () -> T,
): T {
    require(timeoutMillis > 0L)
    val prepared = withTimeoutOrNull(timeoutMillis) { PreparedValue(block()) }
        ?: throw ProactivePreparationTimeoutException(step)
    return prepared.value
}
