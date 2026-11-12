/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.service

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local execution metadata only; never store model text, prompts, headers, or credentials. */
internal object ProactiveExecutionStore {
    private const val PREFS = "proactive_execution_results"
    private const val KEY = "recent_executions"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun history(context: Context): List<ProactiveExecution> = synchronized(lock) {
        runCatching { read(context) }.getOrDefault(emptyList())
    }

    fun recoverAfterProcessStart(context: Context) {
        runCatching {
            synchronized(lock) {
                val current = read(context)
                val recovered = current.recoverUnfinishedProactiveExecutions(System.currentTimeMillis())
                if (recovered != current) write(context, recovered)
            }
        }.onFailure { Log.w("ProactiveExecution", "Unable to finalize prior process results", it) }
    }

    fun begin(
        context: Context,
        scheduledAt: Long?,
        status: ProactiveExecutionStatus = ProactiveExecutionStatus.PREPARING,
        detail: String? = null,
    ): String? = runCatching {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val execution = ProactiveExecution(
                id = UUID.randomUUID().toString(),
                scheduledAt = scheduledAt,
                startedAt = now,
                updatedAt = now,
                status = status,
                detail = detail,
            )
            write(context, (listOf(execution) + read(context)).take(PROACTIVE_EXECUTION_HISTORY_LIMIT))
            execution.id
        }
    }.onFailure { Log.w("ProactiveExecution", "Unable to record execution start", it) }.getOrNull()

    fun update(
        context: Context,
        id: String,
        status: ProactiveExecutionStatus,
        conversationId: String? = null,
        detail: String? = null,
    ) {
        runCatching {
            synchronized(lock) {
                val current = read(context)
                val updated = current.updateProactiveExecution(
                    id = id,
                    status = status,
                    now = System.currentTimeMillis(),
                    conversationId = conversationId,
                    detail = detail,
                )
                if (updated != current) write(context, updated)
            }
        }.onFailure { Log.w("ProactiveExecution", "Unable to update execution result", it) }
    }

    private fun read(context: Context): List<ProactiveExecution> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ProactiveExecution>>(encoded).take(PROACTIVE_EXECUTION_HISTORY_LIMIT)
        }
            .getOrDefault(emptyList())
    }

    private fun write(context: Context, history: List<ProactiveExecution>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(history)).apply()
    }
}
