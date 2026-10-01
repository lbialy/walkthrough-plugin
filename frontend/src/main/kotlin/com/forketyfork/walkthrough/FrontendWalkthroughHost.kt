@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mirrors the backend's active walkthrough session as a popup in this (frontend) process. The
 * backend state is collected in [durable], which re-subscribes after a connection hitch and
 * immediately receives the full current snapshot, so reconciliation is idempotent. All popup state
 * is confined to the EDT.
 */
@Service(Service.Level.PROJECT)
class FrontendWalkthroughHost(private val project: Project, private val coroutineScope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    private class ActivePopup(val session: WalkthroughUiSession, var disposable: Disposable?)

    private var active: ActivePopup? = null
    private val locallyDismissed = BoundedIdSet(MAX_REMEMBERED_SESSIONS)
    private val reported = BoundedIdSet(MAX_REMEMBERED_SESSIONS)

    val scope: CoroutineScope
        get() = coroutineScope

    fun start() {
        if (!started.compareAndSet(false, true)) return
        coroutineScope.launch {
            durable {
                // File ids are bound to this connection's client session, so a reconnect starts afresh.
                val fileIds = SessionFileIds()
                WalkthroughRpcApi.getInstance().sessionState(project.projectId()).collect { state ->
                    val snapshot = fileIds.resolveFor(state.active)
                    withContext(Dispatchers.EDT) { reconcile(state.active, snapshot) }
                }
            }
        }
    }

    private fun reconcile(state: WalkthroughSessionStateDto?, fileIds: Map<String, VirtualFileId>) {
        val current = active
        if (current != null && current.session.id == state?.sessionId) {
            current.session.addFiles(toFiles(fileIds))
            current.session.apply(state)
            return
        }
        current?.let(::closeFromBackend)
        if (state != null && !locallyDismissed.contains(state.sessionId)) {
            showPopup(state, fileIds)
        }
    }

    private fun toFiles(fileIds: Map<String, VirtualFileId>): Map<String, VirtualFile> =
        fileIds.mapNotNull { (path, fileId) -> fileId.virtualFile()?.let { file -> path to file } }.toMap()

    private fun showPopup(state: WalkthroughSessionStateDto, fileIds: Map<String, VirtualFileId>) {
        val sessionId = state.sessionId
        val session = WalkthroughUiSession(state) { question, parentLabel ->
            launchCall("submit question") {
                submitQuestion(project.projectId(), sessionId, question, parentLabel)
            }
        }
        session.addFiles(toFiles(fileIds))
        val popup = ActivePopup(session, disposable = null)
        active = popup
        val disposable = when (session.targetKind) {
            WalkthroughTargetKind.File -> showWalkthroughSession(project, session) { onPopupDisposed(popup) }

            WalkthroughTargetKind.Diff ->
                showDiffWalkthroughSession(project, session, coroutineScope) { onPopupDisposed(popup) }
        }
        popup.disposable = disposable
        if (disposable == null) active = null
        report(sessionId, if (disposable == null) ShowResultDto.NoEditor(NO_EDITOR_MESSAGE) else ShowResultDto.Shown)
    }

    /** The backend moved on (new session, dismissal elsewhere): close the popup without echoing a dismiss. */
    private fun closeFromBackend(popup: ActivePopup) {
        active = null
        popup.disposable?.let(Disposer::dispose)
    }

    /** Runs when the popup's disposable goes away; if it wasn't the backend closing it, tell the backend. */
    private fun onPopupDisposed(popup: ActivePopup) {
        if (active !== popup) return
        active = null
        val sessionId = popup.session.id
        locallyDismissed.add(sessionId)
        launchCall("dismiss") { dismiss(project.projectId(), sessionId) }
    }

    /** Reports the outcome of the first display of [sessionId] only; a reconnect replay must not re-report. */
    private fun report(sessionId: String, result: ShowResultDto) {
        if (!reported.add(sessionId)) return
        launchCall("report shown") { reportShown(project.projectId(), sessionId, result) }
    }

    private fun launchCall(operation: String, call: suspend WalkthroughRpcApi.() -> Unit) {
        coroutineScope.launch { callBackend(operation, call) }
    }

    /**
     * File ids of the active session, fetched from the backend as new paths appear (initial items and
     * inserted tangents). Confined to the state-collecting coroutine.
     */
    private inner class SessionFileIds {
        private var sessionId: String? = null
        private val fileIds = HashMap<String, VirtualFileId>()

        suspend fun resolveFor(state: WalkthroughSessionStateDto?): Map<String, VirtualFileId> {
            if (state?.sessionId != sessionId) {
                sessionId = state?.sessionId
                fileIds.clear()
            }
            val missing = state?.items.orEmpty()
                .filter { resolved -> resolved.lineCount != null }
                .mapNotNull { resolved -> resolved.item.file }
                .distinct()
                .filterNot(fileIds::containsKey)
            if (missing.isNotEmpty()) {
                callBackend("resolve files") { resolveFiles(project.projectId(), missing) }?.let(fileIds::putAll)
            }
            return fileIds.toMap()
        }
    }

    /** Insertion-ordered id set that forgets the oldest ids beyond [capacity]. EDT-confined. */
    private class BoundedIdSet(private val capacity: Int) {
        private val ids = LinkedHashSet<String>()

        fun contains(id: String): Boolean = id in ids

        fun add(id: String): Boolean {
            val added = ids.add(id)
            while (ids.size > capacity) ids.remove(ids.first())
            return added
        }
    }

    companion object {
        private const val MAX_REMEMBERED_SESSIONS = 128
        const val NO_EDITOR_MESSAGE = "No active editor"

        fun getInstance(project: Project): FrontendWalkthroughHost =
            project.getService(FrontendWalkthroughHost::class.java)
    }
}
