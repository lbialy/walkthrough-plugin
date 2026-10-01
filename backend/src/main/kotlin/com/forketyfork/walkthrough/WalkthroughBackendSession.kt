package com.forketyfork.walkthrough

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class WalkthroughTangentQuestion(val question: String, val parentLabel: String?)

sealed interface WalkthroughQuestionAwaitResult {
    data class Received(val question: WalkthroughTangentQuestion) : WalkthroughQuestionAwaitResult
    data object Dismissed : WalkthroughQuestionAwaitResult
    data object WaitingExpired : WalkthroughQuestionAwaitResult
    data object Replaced : WalkthroughQuestionAwaitResult
}

/** Result of [WalkthroughBackendSession.insertTangents]: the labeled items and where the popup should jump. */
data class WalkthroughTangentInsertion(val inserted: List<WalkthroughItem>, val focusIndex: Int)

private class WalkthroughQuestionWaiter {
    val result = CompletableDeferred<WalkthroughQuestionAwaitResult>()
}

private data class WalkthroughQuestionWaitRegistration(
    val waiter: WalkthroughQuestionWaiter?,
    val previousWaiter: WalkthroughQuestionWaiter?,
    val immediateResult: WalkthroughQuestionAwaitResult?,
)

/**
 * Backend half of a walkthrough session: the item list, the question waiter state machine and the
 * question status the popup displays. The popup itself (and the current step index) lives on the
 * frontend; every observable change here is announced through [onStateChanged] so the registry can
 * republish a snapshot.
 *
 * [items], [questionStatus] and [loading] are immutable snapshots behind volatile fields, so the
 * registry can read them without taking [questionLock] (which [onStateChanged] may be called under).
 */
@Suppress("TooManyFunctions")
class WalkthroughBackendSession(
    val id: String,
    initialItems: List<ResolvedItemDto>,
    val targetKind: WalkthroughTargetKind,
    val diffDescriptors: List<DiffWalkthroughDescriptor>,
    val acceptsQuestions: Boolean,
    private val notListeningGracePeriodMillis: Long = DEFAULT_NOT_LISTENING_GRACE_PERIOD_MILLIS,
    private val onStateChanged: () -> Unit = {},
) {
    @Volatile
    var items: List<ResolvedItemDto> = initialItems.toList()
        private set

    @Volatile
    var questionStatus: WalkthroughQuestionStatus = WalkthroughQuestionStatus.AgentNotWaiting
        private set

    @Volatile
    var loading: Boolean = false
        private set

    @Volatile
    var historyRecordId: String? = null

    private val questionLock = Any()
    private val itemsLock = Any()
    private var activeQuestionWaiter: WalkthroughQuestionWaiter? = null
    private var pendingQuestion: WalkthroughTangentQuestion? = null
    private var inFlightQuestion: WalkthroughTangentQuestion? = null
    private val disposed = CompletableDeferred<Unit>()
    private val sessionScope = CoroutineScope(SupervisorJob())
    private var pendingNotListeningJob: Job? = null

    val isDismissed: Boolean
        get() = disposed.isCompleted

    fun snapshotItems(): List<WalkthroughItem> = items.map { it.item }

    /**
     * Records a question typed into the popup. [parentLabel] is the label of the step the user was
     * viewing; the frontend owns the current step, so it sends the label along.
     */
    fun submitQuestion(text: String, parentLabel: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // The frontend may have optimistically shown "queued"; republish the real status.
            onStateChanged()
            return
        }
        val question = WalkthroughTangentQuestion(trimmed, parentLabel)
        val waiter = synchronized(questionLock) {
            when {
                disposed.isCompleted -> null

                pendingQuestion != null -> null

                questionStatus == WalkthroughQuestionStatus.ProcessingQuestion -> null

                activeQuestionWaiter != null -> {
                    activeQuestionWaiter.also {
                        activeQuestionWaiter = null
                        inFlightQuestion = question
                        cancelPendingAgentNotWaitingLocked()
                        setQuestionStatus(WalkthroughQuestionStatus.ProcessingQuestion)
                    }
                }

                else -> {
                    pendingQuestion = question
                    cancelPendingAgentNotWaitingLocked()
                    setQuestionStatus(WalkthroughQuestionStatus.QuestionQueued)
                    null
                }
            }
        }
        waiter?.result?.complete(WalkthroughQuestionAwaitResult.Received(question))
        onStateChanged()
    }

    suspend fun awaitQuestion(): WalkthroughTangentQuestion? =
        when (val result = awaitQuestionResult(timeoutMillis = null)) {
            is WalkthroughQuestionAwaitResult.Received -> result.question
            else -> null
        }

    suspend fun awaitQuestionResult(timeoutMillis: Long?): WalkthroughQuestionAwaitResult = coroutineScope {
        val registration = registerQuestionWaiter()
        registration.previousWaiter?.result?.complete(WalkthroughQuestionAwaitResult.Replaced)
        registration.immediateResult?.let { return@coroutineScope it }

        val waiter = registration.waiter ?: return@coroutineScope WalkthroughQuestionAwaitResult.Dismissed
        val timeoutJob = timeoutMillis?.let { timeout ->
            launch {
                delay(timeout)
                expireQuestionWaiter(waiter)
            }
        }
        try {
            waiter.result.await()
        } finally {
            timeoutJob?.cancel()
            clearQuestionWaiter(waiter)
        }
    }

    /**
     * Splices [newItems] into the list as children of [parentLabel]. The items are resolved by the
     * caller; labels are assigned here. Returns the labeled items and the index the popup should
     * navigate to.
     */
    fun insertTangents(parentLabel: String, newItems: List<ResolvedItemDto>): WalkthroughTangentInsertion {
        require(newItems.isNotEmpty()) { "newItems must not be empty" }
        val insertion = synchronized(itemsLock) {
            val current = items
            val parentIndex = current.indexOfFirst { it.item.label == parentLabel }
            require(parentIndex >= 0) { "No item with label '$parentLabel'" }

            val directChildPattern = Regex("^${Regex.escape(parentLabel)}\\.\\d+$")
            val existingDirectChildren = current.count { it.item.label?.matches(directChildPattern) == true }

            val parentPrefix = "$parentLabel."
            var lastSubtreeIndex = parentIndex
            current.forEachIndexed { index, resolved ->
                if (resolved.item.label?.startsWith(parentPrefix) == true) {
                    lastSubtreeIndex = index
                }
            }

            val labeled = newItems.mapIndexed { offset, resolved ->
                resolved.copy(
                    item = resolved.item.copy(
                        label = "$parentLabel.${existingDirectChildren + offset + 1}",
                        parentLabel = parentLabel,
                    ),
                )
            }
            items = current.toMutableList().apply { addAll(lastSubtreeIndex + 1, labeled) }
            WalkthroughTangentInsertion(labeled.map { it.item }, lastSubtreeIndex + 1)
        }
        synchronized(questionLock) {
            inFlightQuestion = null
            scheduleAgentNotWaitingLocked()
        }
        onStateChanged()
        return insertion
    }

    fun dismiss() {
        if (disposed.complete(Unit)) {
            val waiter = synchronized(questionLock) {
                activeQuestionWaiter.also {
                    activeQuestionWaiter = null
                    pendingQuestion = null
                    inFlightQuestion = null
                    cancelPendingAgentNotWaitingLocked()
                    setQuestionStatus(WalkthroughQuestionStatus.AgentNotWaiting)
                }
            }
            waiter?.result?.complete(WalkthroughQuestionAwaitResult.Dismissed)
            sessionScope.coroutineContext[Job]?.cancel()
        }
    }

    private fun registerQuestionWaiter(): WalkthroughQuestionWaitRegistration {
        val waiter = WalkthroughQuestionWaiter()
        val registration = synchronized(questionLock) {
            when {
                disposed.isCompleted -> WalkthroughQuestionWaitRegistration(
                    waiter = null,
                    previousWaiter = null,
                    immediateResult = WalkthroughQuestionAwaitResult.Dismissed,
                )

                inFlightQuestion != null -> {
                    cancelPendingAgentNotWaitingLocked()
                    setQuestionStatus(WalkthroughQuestionStatus.ProcessingQuestion)
                    WalkthroughQuestionWaitRegistration(
                        waiter = null,
                        previousWaiter = null,
                        immediateResult = WalkthroughQuestionAwaitResult.Received(requireNotNull(inFlightQuestion)),
                    )
                }

                pendingQuestion != null -> {
                    val question = pendingQuestion
                    pendingQuestion = null
                    inFlightQuestion = question
                    cancelPendingAgentNotWaitingLocked()
                    setQuestionStatus(WalkthroughQuestionStatus.ProcessingQuestion)
                    WalkthroughQuestionWaitRegistration(
                        waiter = null,
                        previousWaiter = null,
                        immediateResult = WalkthroughQuestionAwaitResult.Received(requireNotNull(question)),
                    )
                }

                else -> {
                    val previousWaiter = activeQuestionWaiter
                    activeQuestionWaiter = waiter
                    cancelPendingAgentNotWaitingLocked()
                    setQuestionStatus(WalkthroughQuestionStatus.WaitingForQuestion)
                    WalkthroughQuestionWaitRegistration(
                        waiter = waiter,
                        previousWaiter = previousWaiter,
                        immediateResult = null,
                    )
                }
            }
        }
        onStateChanged()
        return registration
    }

    private fun expireQuestionWaiter(waiter: WalkthroughQuestionWaiter) {
        val expired = synchronized(questionLock) {
            if (activeQuestionWaiter === waiter) {
                activeQuestionWaiter = null
                scheduleAgentNotWaitingLocked()
                true
            } else {
                false
            }
        }
        if (expired) {
            onStateChanged()
            waiter.result.complete(WalkthroughQuestionAwaitResult.WaitingExpired)
        }
    }

    private fun clearQuestionWaiter(waiter: WalkthroughQuestionWaiter) {
        val cleared = synchronized(questionLock) {
            if (activeQuestionWaiter === waiter) {
                activeQuestionWaiter = null
                scheduleAgentNotWaitingLocked()
                true
            } else {
                false
            }
        }
        if (cleared) onStateChanged()
    }

    private fun setQuestionStatus(status: WalkthroughQuestionStatus) {
        questionStatus = status
        loading = status == WalkthroughQuestionStatus.ProcessingQuestion
    }

    /**
     * Defers the transition to [WalkthroughQuestionStatus.AgentNotWaiting] by [notListeningGracePeriodMillis]
     * to avoid briefly showing the "agent is not listening" warning when the MCP client cancels and
     * immediately re-issues the await call. The spinner ([loading]) is cleared immediately so it
     * does not keep spinning during the grace window after the question has actually been answered.
     * Must be invoked while holding [questionLock].
     */
    private fun scheduleAgentNotWaitingLocked() {
        cancelPendingAgentNotWaitingLocked()
        if (notListeningGracePeriodMillis <= 0L) {
            setQuestionStatus(WalkthroughQuestionStatus.AgentNotWaiting)
            return
        }
        // Stop the spinner now even though the visible status text is deferred: the question is
        // done, only the AgentNotWaiting warning flash needs the grace window.
        loading = false
        lateinit var scheduledJob: Job
        scheduledJob = sessionScope.launch {
            delay(notListeningGracePeriodMillis)
            val changed = synchronized(questionLock) { applyPendingAgentNotWaitingLocked(scheduledJob) }
            if (changed) onStateChanged()
        }
        pendingNotListeningJob = scheduledJob
    }

    /** Must be invoked while holding [questionLock]. Returns whether the status changed. */
    private fun applyPendingAgentNotWaitingLocked(scheduledJob: Job): Boolean {
        if (pendingNotListeningJob !== scheduledJob) return false
        pendingNotListeningJob = null
        val hasListener = activeQuestionWaiter != null || inFlightQuestion != null || pendingQuestion != null
        if (!hasListener) setQuestionStatus(WalkthroughQuestionStatus.AgentNotWaiting)
        return !hasListener
    }

    /** Must be invoked while holding [questionLock]. */
    private fun cancelPendingAgentNotWaitingLocked() {
        pendingNotListeningJob?.cancel()
        pendingNotListeningJob = null
    }

    companion object {
        /**
         * How long to wait after the agent stops listening before flipping the popup to
         * [WalkthroughQuestionStatus.AgentNotWaiting]. MCP clients typically reconnect within
         * milliseconds; the grace period avoids a visible warning flash during normal retries.
         */
        const val DEFAULT_NOT_LISTENING_GRACE_PERIOD_MILLIS: Long = 5_000L
    }
}
