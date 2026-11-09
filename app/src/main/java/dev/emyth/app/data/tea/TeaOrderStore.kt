package dev.emyth.app.data.tea

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A small local notification ledger. Durable transitions precede model dispatch. */
class TeaOrderStore private constructor(private val preferences: SharedPreferences) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var state = readState()
    private val mutableEvents = MutableStateFlow(state.events)
    val events: StateFlow<List<TeaOrderEvent>> = mutableEvents.asStateFlow()

    init {
        val recovered = TeaOrderStateReducer.recover(state, System.currentTimeMillis())
        if (recovered != state) {
            save(recovered)
            state = recovered
            mutableEvents.value = recovered.events
        }
    }

    fun record(candidate: TeaOrderCandidate): TeaOrderRecordResult = synchronized(lock) {
        val transition = TeaOrderStateReducer.record(state, candidate, UUID.randomUUID().toString())
        commit(transition.state)
        transition.result
    }

    /** Claim is atomic and is persisted before any request is allowed to start. */
    fun claim(id: String): TeaOrderEvent? = synchronized(lock) {
        val transition = TeaOrderStateReducer.claim(state, id, System.currentTimeMillis())
        commit(transition.state)
        transition.result
    }

    /** Call immediately before a model request or any tool can start. */
    fun markGenerationStarted(id: String): Boolean = mutate {
        TeaOrderStateReducer.markGenerationStarted(it, id, System.currentTimeMillis())
    }

    fun markDelivered(id: String, conversationId: String?): Boolean = mutate {
        TeaOrderStateReducer.delivered(it, id, conversationId, System.currentTimeMillis())
    }

    fun markFailed(id: String, reason: TeaOrderFailure): Boolean = mutate {
        TeaOrderStateReducer.failed(it, id, reason, System.currentTimeMillis())
    }

    /** Retry exhaustion and transport checks cannot cancel a service which has already claimed this event. */
    fun markPendingFailed(id: String, reason: TeaOrderFailure): Boolean = mutate {
        TeaOrderStateReducer.pendingFailed(it, id, reason, System.currentTimeMillis())
    }

    /** Only safe while no request/tool has started; never use this after a generation exception. */
    fun releaseBeforeStart(id: String): Boolean = mutate {
        TeaOrderStateReducer.releaseBeforeStart(it, id, System.currentTimeMillis())
    }

    fun clearHistory(): Boolean = synchronized(lock) {
        commit(TeaOrderStateReducer.clearHistory(state))
    }

    private fun mutate(reduce: (TeaOrderState) -> TeaOrderTransition<Boolean>): Boolean = synchronized(lock) {
        val transition = reduce(state)
        commit(transition.state) && transition.result
    }

    private fun readState(): TeaOrderState = try {
        preferences.getString(STATE_KEY, null)?.let { json.decodeFromString<TeaOrderState>(it) } ?: TeaOrderState()
    } catch (exception: Exception) {
        throw IOException("Unable to read the local tea notification ledger", exception)
    }

    private fun commit(next: TeaOrderState): Boolean {
        if (next == state) return true
        save(next)
        state = next
        mutableEvents.value = next.events
        return true
    }

    private fun save(next: TeaOrderState) {
        try {
            if (!preferences.edit().putString(STATE_KEY, json.encodeToString(next)).commit()) {
                throw IOException("Unable to persist the local tea notification ledger")
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException("Unable to persist the local tea notification ledger", exception)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "tea_order_ledger"
        private const val STATE_KEY = "state_v1"
        @Volatile private var instance: TeaOrderStore? = null

        fun get(context: Context): TeaOrderStore = instance ?: synchronized(this) {
            instance ?: TeaOrderStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
