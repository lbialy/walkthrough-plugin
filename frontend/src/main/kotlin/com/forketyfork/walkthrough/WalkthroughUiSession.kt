package com.forketyfork.walkthrough

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.intellij.openapi.vfs.VirtualFile

/**
 * Frontend half of a walkthrough session: the Compose state the popup renders. The backend owns the
 * item list and question status and streams them as [WalkthroughSessionStateDto] snapshots; the
 * current step is owned here. [apply] must be called on the EDT.
 */
class WalkthroughUiSession(
    initialState: WalkthroughSessionStateDto,
    private val onQuestionSubmitted: (question: String, parentLabel: String?) -> Unit,
) {
    val id: String = initialState.sessionId
    val targetKind: WalkthroughTargetKind = initialState.targetKind
    val diffDescriptors: List<DiffWalkthroughDescriptor> = initialState.diffDescriptors
    val acceptsQuestions: Boolean = initialState.acceptsQuestions

    val items: SnapshotStateList<WalkthroughItem> = mutableStateListOf()
    val currentIndexState: MutableIntState = mutableIntStateOf(0)
    val questionStatusState: MutableState<WalkthroughQuestionStatus> =
        mutableStateOf(WalkthroughQuestionStatus.AgentNotWaiting)
    val loadingState: MutableState<Boolean> = mutableStateOf(false)

    private var resolvedItems: Map<WalkthroughItem, ResolvedItemDto> = emptyMap()
    private var files: Map<String, VirtualFile> = emptyMap()
    private var appliedRevision = Long.MIN_VALUE
    private var appliedFocusSeq: Long? = null

    init {
        apply(initialState)
    }

    /** Backend validation (line count) of [item], if the item is part of this session. */
    fun resolved(item: WalkthroughItem): ResolvedItemDto? = resolvedItems[item]

    /** The frontend file for [item]'s project-relative path, once the backend has resolved it. */
    fun file(item: WalkthroughItem): VirtualFile? = item.file?.let(files::get)

    /** Adds files resolved by the backend, keyed by project-relative path. */
    fun addFiles(resolvedFiles: Map<String, VirtualFile>) {
        if (resolvedFiles.isNotEmpty()) files = files + resolvedFiles
    }

    /**
     * Reconciles with a backend snapshot. Snapshots for another session or with a revision that was
     * already applied are ignored, so a reconnect replay is harmless. Returns whether anything was applied.
     */
    fun apply(state: WalkthroughSessionStateDto): Boolean {
        if (state.sessionId != id || state.revision <= appliedRevision) return false
        appliedRevision = state.revision

        val newItems = state.items.map { it.item }
        if (newItems != items.toList()) {
            items.clear()
            items.addAll(newItems)
        }
        resolvedItems = state.items.associateBy { it.item }
        questionStatusState.value = state.questionStatus
        loadingState.value = state.loading

        val focus = state.focusRequest
        if (focus != null && focus.seq != appliedFocusSeq) {
            appliedFocusSeq = focus.seq
            currentIndexState.intValue = focus.index
        }
        currentIndexState.intValue = currentIndexState.intValue.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        return true
    }

    /**
     * Sends a question typed into the popup, tagged with the label of the step being viewed. The
     * status flips to queued optimistically; the next backend snapshot carries the real status.
     */
    fun submitQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val parentLabel = items.getOrNull(currentIndexState.intValue)?.label
        questionStatusState.value = WalkthroughQuestionStatus.QuestionQueued
        onQuestionSubmitted(trimmed, parentLabel)
    }
}
