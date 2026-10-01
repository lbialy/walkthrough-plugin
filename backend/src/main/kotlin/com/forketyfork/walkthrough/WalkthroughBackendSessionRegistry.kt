package com.forketyfork.walkthrough

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A freshly started session plus the acknowledgement the frontend sends once it has shown (or failed to show) it. */
class WalkthroughSessionStart(val session: WalkthroughBackendSession, val shown: CompletableDeferred<ShowResultDto>)

/**
 * Owns the backend walkthrough sessions of a project and publishes the active one as a
 * [WalkthroughUiStateDto] snapshot. At most one session is active: starting a new one dismisses the
 * previous one, like opening a new popup used to close the old one.
 */
@Service(Service.Level.PROJECT)
class WalkthroughBackendSessionRegistry {
    /** Overridable by tests; applies to sessions started afterwards. */
    internal var notListeningGracePeriodMillis: Long =
        WalkthroughBackendSession.DEFAULT_NOT_LISTENING_GRACE_PERIOD_MILLIS

    private val sessions = ConcurrentHashMap<String, WalkthroughBackendSession>()
    private val pendingShows = ConcurrentHashMap<String, CompletableDeferred<ShowResultDto>>()
    private val dismissedSessionIds = ConcurrentHashMap.newKeySet<String>()
    private val dismissedSessionOrder = ConcurrentLinkedQueue<String>()

    private val publishLock = Any()
    private var activeSessionId: String? = null
    private var revision = 0L
    private var focusSeq = 0L
    private var focusRequest: FocusRequestDto? = null
    private val mutableState = MutableStateFlow(WalkthroughUiStateDto())

    val state: StateFlow<WalkthroughUiStateDto> = mutableState.asStateFlow()

    fun start(
        items: List<ResolvedItemDto>,
        acceptsQuestions: Boolean,
        targetKind: WalkthroughTargetKind = WalkthroughTargetKind.File,
        diffDescriptors: List<DiffWalkthroughDescriptor> = emptyList(),
    ): WalkthroughSessionStart {
        val id = UUID.randomUUID().toString()
        lateinit var session: WalkthroughBackendSession
        session = WalkthroughBackendSession(
            id = id,
            initialItems = items,
            targetKind = targetKind,
            diffDescriptors = diffDescriptors,
            acceptsQuestions = acceptsQuestions,
            notListeningGracePeriodMillis = notListeningGracePeriodMillis,
            onStateChanged = { publishIfActive(session.id) },
        )
        val shown = CompletableDeferred<ShowResultDto>()
        sessions[id] = session
        pendingShows[id] = shown
        val previousId = synchronized(publishLock) {
            val previous = activeSessionId
            activeSessionId = id
            focusRequest = FocusRequestDto(++focusSeq, 0)
            publishLocked()
            previous
        }
        previousId?.let(::remove)
        return WalkthroughSessionStart(session, shown)
    }

    fun get(id: String): WalkthroughBackendSession? = sessions[id]

    /** Completes the pending show of [sessionId]; the first report wins. */
    fun reportShown(sessionId: String, result: ShowResultDto) {
        pendingShows.remove(sessionId)?.complete(result)
    }

    fun consumeDismissed(id: String): Boolean = dismissedSessionIds.remove(id)

    /** Dismisses [id] and remembers it so a pending await can report `dismissed`. */
    fun remove(id: String) {
        val session = sessions.remove(id) ?: return
        pendingShows.remove(id)?.cancel()
        session.dismiss()
        rememberDismissed(id)
        clearActive(id)
    }

    /** Drops [id] without remembering it as dismissed: the agent never learned about it. */
    fun discard(id: String) {
        val session = sessions.remove(id) ?: return
        pendingShows.remove(id)?.cancel()
        session.dismiss()
        clearActive(id)
    }

    /** Splices tangents into [session] and asks the frontend to jump to the first one. */
    fun insertTangents(
        session: WalkthroughBackendSession,
        parentLabel: String,
        newItems: List<ResolvedItemDto>,
    ): List<WalkthroughItem> {
        val insertion = session.insertTangents(parentLabel, newItems)
        synchronized(publishLock) {
            if (activeSessionId == session.id) {
                focusRequest = FocusRequestDto(++focusSeq, insertion.focusIndex)
                publishLocked()
            }
        }
        return insertion.inserted
    }

    private fun clearActive(id: String) {
        synchronized(publishLock) {
            if (activeSessionId == id) {
                activeSessionId = null
                focusRequest = null
                publishLocked()
            }
        }
    }

    private fun publishIfActive(id: String) {
        synchronized(publishLock) {
            if (activeSessionId == id) publishLocked()
        }
    }

    /** Must be invoked while holding [publishLock]. */
    private fun publishLocked() {
        val session = activeSessionId?.let(sessions::get)
        revision += 1
        mutableState.value = WalkthroughUiStateDto(
            active = session?.let {
                WalkthroughSessionStateDto(
                    sessionId = it.id,
                    revision = revision,
                    targetKind = it.targetKind,
                    acceptsQuestions = it.acceptsQuestions,
                    diffDescriptors = it.diffDescriptors,
                    items = it.items,
                    questionStatus = it.questionStatus,
                    loading = it.loading,
                    focusRequest = focusRequest,
                )
            },
        )
    }

    private fun rememberDismissed(id: String) {
        if (dismissedSessionIds.add(id)) {
            dismissedSessionOrder.add(id)
        }
        while (dismissedSessionIds.size > MAX_DISMISSED_SESSIONS) {
            dismissedSessionOrder.poll()?.let(dismissedSessionIds::remove) ?: break
        }
    }

    companion object {
        private const val MAX_DISMISSED_SESSIONS = 128

        fun getInstance(project: Project): WalkthroughBackendSessionRegistry =
            project.getService(WalkthroughBackendSessionRegistry::class.java)
    }
}
