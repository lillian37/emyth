package dev.emyth.app.data.tea

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class TeaOrderStatus { OBSERVED, PENDING, PROCESSING, DELIVERED, FAILED }

@Serializable
enum class TeaOrderFailure {
    INTERRUPTED,
    STALE_NOTIFICATION,
    INVALID_NOTIFICATION_TIME,
    ASSISTANT_UNAVAILABLE,
    FEATURE_DISABLED,
    GENERATION_FAILED,
    CANCELLED,
    SERVICE_START_FAILED,
    SESSION_BUSY,
    DELIVERY_SKIPPED,
}

enum class TeaOrderRecordRejection { WRONG_SOURCE, IGNORED, DUPLICATE, PERSISTENCE_FAILED }

/** Input is transient: notification bodies and notification keys are never persisted. */
data class TeaOrderCandidate(
    val packageName: String,
    val notificationKey: String,
    val postedAtMillis: Long,
    val observedAtMillis: Long,
    val targetAssistantId: String,
    val targetAssistantName: String,
    val detection: TeaOrderDetection,
)

@Serializable
data class TeaOrderEvent(
    val id: String,
    val sourcePackage: String = TeaOrderStateReducer.TAOBAO_PACKAGE,
    val detectedAtMillis: Long,
    val updatedAtMillis: Long,
    val notificationPostedAtMillis: Long,
    val notificationKeyDigest: String,
    val detectionStatus: TeaOrderDetectionStatus,
    val stage: TeaOrderStage,
    val reason: String,
    val matchedTeaKeywords: List<String>,
    val matchedDeliveryKeywords: List<String>,
    val orderId: String?,
    val targetAssistantId: String,
    val targetAssistantName: String,
    val status: TeaOrderStatus,
    val failureReason: TeaOrderFailure? = null,
    val conversationId: String? = null,
    val generationStartedAtMillis: Long? = null,
)

@Serializable
data class TeaOrderReceipt(
    val eventId: String,
    val orderDigest: String?,
    val notificationKeyDigest: String,
    val detectedAtMillis: Long,
    val stage: TeaOrderStage = TeaOrderStage.UNKNOWN,
    val teaDigest: String? = null,
)

@Serializable
data class TeaOrderState(
    val events: List<TeaOrderEvent> = emptyList(),
    val receipts: List<TeaOrderReceipt> = emptyList(),
)

data class TeaOrderRecordResult(
    val event: TeaOrderEvent? = null,
    val shouldProcess: Boolean = false,
    val duplicate: Boolean = false,
    val rejection: TeaOrderRecordRejection? = null,
)

data class TeaOrderTransition<out T>(val state: TeaOrderState, val result: T)

/** Pure state transitions, shared by the durable adapter and JVM tests. */
object TeaOrderStateReducer {
    const val TAOBAO_PACKAGE = "com.taobao.taobao"
    const val MAX_EVENTS = 40
    const val MAX_RECEIPTS = 160
    const val FRESH_NOTIFICATION_MILLIS = 30 * 60 * 1000L
    const val KEY_DEDUP_MILLIS = 20 * 60 * 1000L
    const val ORDER_LIFECYCLE_MILLIS = 3 * 60 * 60 * 1000L
    const val ORDER_DEDUP_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private const val FUTURE_TOLERANCE_MILLIS = 60 * 1000L

    fun record(
        state: TeaOrderState,
        candidate: TeaOrderCandidate,
        eventId: String,
    ): TeaOrderTransition<TeaOrderRecordResult> {
        if (candidate.packageName != TAOBAO_PACKAGE) {
            return TeaOrderTransition(state, TeaOrderRecordResult(rejection = TeaOrderRecordRejection.WRONG_SOURCE))
        }
        val detection = candidate.detection
        if (detection.status == TeaOrderDetectionStatus.IGNORED) {
            return TeaOrderTransition(state, TeaOrderRecordResult(rejection = TeaOrderRecordRejection.IGNORED))
        }
        val now = candidate.observedAtMillis
        val keyDigest = digest(candidate.notificationKey)
        val orderId = detection.orderId?.filter { it.isLetterOrDigit() || it == '-' }
            ?.take(64)?.takeIf { it.length >= 6 }
        val orderDigest = orderId?.let(::digest)
        val candidateTeaDigest = teaDigest(detection.matchedTeaKeywords)
        val timeFailure = notificationFailure(candidate.postedAtMillis, now)
        val receipts = state.receipts.filter { isWithin(now, it.detectedAtMillis, ORDER_DEDUP_MILLIS) }
        val exactEvent = state.events.firstOrNull { orderId != null && it.orderId == orderId }
        val exactReceipt = receipts.firstOrNull { orderDigest != null && it.orderDigest == orderDigest }
        val existing = exactEvent ?: if (exactReceipt == null) {
            state.events.firstOrNull { event ->
                (orderId == null && event.notificationKeyDigest == keyDigest &&
                    (isWithin(now, event.detectedAtMillis, KEY_DEDUP_MILLIS) || followsLifecycle(
                        now, event.detectedAtMillis, event.stage, detection.stage,
                        teaDigest(event.matchedTeaKeywords), candidateTeaDigest,
                    ))) ||
                (orderId != null && event.orderId == null && event.notificationKeyDigest == keyDigest &&
                    completesIdentity(
                        now, event.detectedAtMillis, event.stage, detection.stage,
                        teaDigest(event.matchedTeaKeywords), candidateTeaDigest,
                    ))
            }
        } else null
        val priorReceipt = if (existing == null) {
            exactReceipt ?: receipts.firstOrNull { receipt ->
                (orderDigest == null && receipt.notificationKeyDigest == keyDigest &&
                    (isWithin(now, receipt.detectedAtMillis, KEY_DEDUP_MILLIS) || followsLifecycle(
                        now, receipt.detectedAtMillis, receipt.stage, detection.stage,
                        receipt.teaDigest, candidateTeaDigest,
                    ))) ||
                    (orderDigest != null && receipt.orderDigest == null &&
                        receipt.notificationKeyDigest == keyDigest && completesIdentity(
                            now, receipt.detectedAtMillis, receipt.stage, detection.stage,
                            receipt.teaDigest, candidateTeaDigest,
                        ))
            }
        } else null
        if (priorReceipt != null) {
            val updatedReceipt = priorReceipt.copy(
                notificationKeyDigest = keyDigest,
                orderDigest = priorReceipt.orderDigest ?: orderDigest,
                stage = maxOf(priorReceipt.stage, detection.stage),
                teaDigest = candidateTeaDigest ?: priorReceipt.teaDigest,
            )
            return TeaOrderTransition(
                state.copy(receipts = receipts.map { if (it.eventId == priorReceipt.eventId) updatedReceipt else it }),
                TeaOrderRecordResult(duplicate = true, rejection = TeaOrderRecordRejection.DUPLICATE),
            )
        }

        val assistantId = safeText(candidate.targetAssistantId, 100)
        val failure = timeFailure ?: TeaOrderFailure.ASSISTANT_UNAVAILABLE.takeIf {
            detection.status == TeaOrderDetectionStatus.CONFIRMED && assistantId.isEmpty()
        }
        val promote = existing?.status == TeaOrderStatus.OBSERVED &&
            detection.status == TeaOrderDetectionStatus.CONFIRMED && failure == null
        val confirmed = detection.status == TeaOrderDetectionStatus.CONFIRMED ||
            existing?.detectionStatus == TeaOrderDetectionStatus.CONFIRMED
        val event = if (existing != null) {
            existing.copy(
                updatedAtMillis = maxOf(existing.updatedAtMillis, now),
                notificationPostedAtMillis = if (promote) candidate.postedAtMillis
                else existing.notificationPostedAtMillis,
                detectionStatus = if (confirmed) TeaOrderDetectionStatus.CONFIRMED else detection.status,
                stage = if (detection.stage.ordinal > existing.stage.ordinal) detection.stage else existing.stage,
                reason = if (detection.status == TeaOrderDetectionStatus.CONFIRMED || !confirmed) {
                    safeText(detection.reason, 160)
                } else existing.reason,
                matchedTeaKeywords = safeKeywords(existing.matchedTeaKeywords + detection.matchedTeaKeywords),
                matchedDeliveryKeywords = safeKeywords(
                    existing.matchedDeliveryKeywords + detection.matchedDeliveryKeywords
                ),
                orderId = existing.orderId ?: orderId,
                targetAssistantId = if (promote) assistantId else existing.targetAssistantId,
                targetAssistantName = if (promote) safeText(candidate.targetAssistantName, 100)
                else existing.targetAssistantName,
                status = if (promote) TeaOrderStatus.PENDING else existing.status,
            )
        } else {
            TeaOrderEvent(
                id = eventId,
                detectedAtMillis = now,
                updatedAtMillis = now,
                notificationPostedAtMillis = candidate.postedAtMillis,
                notificationKeyDigest = keyDigest,
                detectionStatus = detection.status,
                stage = detection.stage,
                reason = safeText(detection.reason, 160),
                matchedTeaKeywords = safeKeywords(detection.matchedTeaKeywords),
                matchedDeliveryKeywords = safeKeywords(detection.matchedDeliveryKeywords),
                orderId = orderId,
                targetAssistantId = assistantId,
                targetAssistantName = safeText(candidate.targetAssistantName, 100),
                status = when {
                    failure != null -> TeaOrderStatus.FAILED
                    confirmed -> TeaOrderStatus.PENDING
                    else -> TeaOrderStatus.OBSERVED
                },
                failureReason = failure,
            )
        }
        val receipt = TeaOrderReceipt(
            eventId = event.id,
            orderDigest = event.orderId?.let(::digest),
            notificationKeyDigest = keyDigest,
            detectedAtMillis = event.detectedAtMillis,
            stage = event.stage,
            teaDigest = teaDigest(event.matchedTeaKeywords),
        )
        val next = TeaOrderState(
            events = boundedEvents(state.events.filterNot { it.id == event.id } + event),
            receipts = (receipts.filterNot { it.eventId == event.id } + receipt)
                .sortedByDescending { it.detectedAtMillis }.take(MAX_RECEIPTS),
        )
        return TeaOrderTransition(
            next,
            TeaOrderRecordResult(
                event = event,
                shouldProcess = event.status == TeaOrderStatus.PENDING && timeFailure == null &&
                    notificationFailure(event.notificationPostedAtMillis, now) == null,
                duplicate = existing != null,
            ),
        )
    }

    fun claim(state: TeaOrderState, id: String, now: Long): TeaOrderTransition<TeaOrderEvent?> {
        val event = state.events.firstOrNull { it.id == id && it.status == TeaOrderStatus.PENDING }
            ?: return TeaOrderTransition(state, null)
        val failure = notificationFailure(event.notificationPostedAtMillis, now)
        if (failure != null) {
            return TeaOrderTransition(
                replace(
                    state,
                    event.copy(status = TeaOrderStatus.FAILED, failureReason = failure, updatedAtMillis = now),
                ),
                null,
            )
        }
        val claimed = event.copy(status = TeaOrderStatus.PROCESSING, updatedAtMillis = now)
        return TeaOrderTransition(replace(state, claimed), claimed)
    }

    fun markGenerationStarted(state: TeaOrderState, id: String, now: Long): TeaOrderTransition<Boolean> {
        val event = state.events.firstOrNull {
            it.id == id && it.status == TeaOrderStatus.PROCESSING && it.generationStartedAtMillis == null
        } ?: return TeaOrderTransition(state, false)
        return TeaOrderTransition(
            replace(state, event.copy(generationStartedAtMillis = now, updatedAtMillis = now)),
            true,
        )
    }

    fun delivered(
        state: TeaOrderState,
        id: String,
        conversationId: String?,
        now: Long,
    ): TeaOrderTransition<Boolean> = updateProcessing(state, id) {
        it.copy(
            status = TeaOrderStatus.DELIVERED,
            updatedAtMillis = now,
            conversationId = conversationId?.let { value -> safeText(value, 100) },
            failureReason = null,
        )
    }

    fun failed(
        state: TeaOrderState,
        id: String,
        reason: TeaOrderFailure,
        now: Long,
    ): TeaOrderTransition<Boolean> {
        val event = state.events.firstOrNull {
            it.id == id && (it.status == TeaOrderStatus.PENDING || it.status == TeaOrderStatus.PROCESSING)
        } ?: return TeaOrderTransition(state, false)
        return TeaOrderTransition(
            replace(state, event.copy(status = TeaOrderStatus.FAILED, failureReason = reason, updatedAtMillis = now)),
            true,
        )
    }

    fun pendingFailed(
        state: TeaOrderState,
        id: String,
        reason: TeaOrderFailure,
        now: Long,
    ): TeaOrderTransition<Boolean> {
        val event = state.events.firstOrNull {
            it.id == id && it.status == TeaOrderStatus.PENDING && it.generationStartedAtMillis == null
        } ?: return TeaOrderTransition(state, false)
        return TeaOrderTransition(
            replace(state, event.copy(status = TeaOrderStatus.FAILED, failureReason = reason, updatedAtMillis = now)),
            true,
        )
    }

    fun releaseBeforeStart(state: TeaOrderState, id: String, now: Long): TeaOrderTransition<Boolean> {
        val event = state.events.firstOrNull {
            it.id == id && it.status == TeaOrderStatus.PROCESSING && it.generationStartedAtMillis == null
        } ?: return TeaOrderTransition(state, false)
        val failure = notificationFailure(event.notificationPostedAtMillis, now)
        return TeaOrderTransition(
            replace(
                state,
                event.copy(
                    status = if (failure == null) TeaOrderStatus.PENDING else TeaOrderStatus.FAILED,
                    failureReason = failure,
                    updatedAtMillis = now,
                ),
            ),
            failure == null,
        )
    }

    /** A process restart never retries a request which may already have contacted the model. */
    fun recover(state: TeaOrderState, now: Long): TeaOrderState = state.copy(
        events = boundedEvents(state.events.map { event ->
            val failure = when (event.status) {
                TeaOrderStatus.PROCESSING -> TeaOrderFailure.INTERRUPTED
                TeaOrderStatus.PENDING -> notificationFailure(event.notificationPostedAtMillis, now)
                else -> null
            }
            if (failure == null) event else event.copy(
                status = TeaOrderStatus.FAILED,
                failureReason = failure,
                updatedAtMillis = now,
            )
        }),
        receipts = state.receipts.filter { isWithin(now, it.detectedAtMillis, ORDER_DEDUP_MILLIS) }
            .take(MAX_RECEIPTS),
    )

    /** Retain bounded digest receipts so clearing the UI does not reset deduplication. */
    fun clearHistory(state: TeaOrderState): TeaOrderState = state.copy(events = emptyList())

    private fun updateProcessing(
        state: TeaOrderState,
        id: String,
        update: (TeaOrderEvent) -> TeaOrderEvent,
    ): TeaOrderTransition<Boolean> {
        val event = state.events.firstOrNull { it.id == id && it.status == TeaOrderStatus.PROCESSING }
            ?: return TeaOrderTransition(state, false)
        return TeaOrderTransition(replace(state, update(event)), true)
    }

    private fun replace(state: TeaOrderState, event: TeaOrderEvent): TeaOrderState =
        state.copy(events = state.events.map { if (it.id == event.id) event else it })

    private fun boundedEvents(events: List<TeaOrderEvent>): List<TeaOrderEvent> {
        val retained = events.sortedWith(
            compareByDescending<TeaOrderEvent> { it.status == TeaOrderStatus.PROCESSING }
                .thenByDescending { it.detectedAtMillis }
        ).take(MAX_EVENTS)
        return retained.sortedByDescending { it.detectedAtMillis }
    }

    private fun notificationFailure(postedAt: Long, now: Long): TeaOrderFailure? = when {
        postedAt <= 0 || now <= 0 || postedAt - now > FUTURE_TOLERANCE_MILLIS ->
            TeaOrderFailure.INVALID_NOTIFICATION_TIME
        now - postedAt > FRESH_NOTIFICATION_MILLIS -> TeaOrderFailure.STALE_NOTIFICATION
        else -> null
    }

    private fun isWithin(now: Long, firstSeen: Long, window: Long): Boolean =
        firstSeen > 0 && now >= firstSeen && now - firstSeen <= window

    private fun followsLifecycle(
        now: Long,
        firstSeen: Long,
        previousStage: TeaOrderStage,
        nextStage: TeaOrderStage,
        previousTeaDigest: String?,
        nextTeaDigest: String?,
    ): Boolean = isWithin(now, firstSeen, ORDER_LIFECYCLE_MILLIS) &&
        nextStage.ordinal >= TeaOrderStage.PREPARING.ordinal && nextStage.ordinal >= previousStage.ordinal &&
        previousTeaDigest != null && previousTeaDigest == nextTeaDigest

    private fun completesIdentity(
        now: Long,
        firstSeen: Long,
        previousStage: TeaOrderStage,
        nextStage: TeaOrderStage,
        previousTeaDigest: String?,
        nextTeaDigest: String?,
    ): Boolean = isWithin(now, firstSeen, KEY_DEDUP_MILLIS) && nextStage.ordinal >= previousStage.ordinal &&
        previousTeaDigest != null && previousTeaDigest == nextTeaDigest

    private fun teaDigest(keywords: List<String>): String? {
        val evidence = safeKeywords(keywords).map { it.lowercase(Locale.ROOT) }.distinct().sorted()
        return evidence.takeIf { it.isNotEmpty() }?.let { digest(it.joinToString("\u0000")) }
    }

    private fun safeKeywords(keywords: List<String>): List<String> =
        keywords.map { safeText(it, 50) }.filter { it.isNotEmpty() }.distinct().take(12)

    private fun safeText(value: String, limit: Int): String =
        value.filterNot { it.isISOControl() }.trim().take(limit)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
