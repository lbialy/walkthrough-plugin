@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.ide.vfs.VirtualFileId
import kotlinx.serialization.Serializable

@Serializable
enum class WalkthroughQuestionStatus {
    AgentNotWaiting,
    WaitingForQuestion,
    QuestionQueued,
    ProcessingQuestion,
}

/**
 * A walkthrough item as the backend validated it for display. [lineCount] is filled in for file
 * walkthrough items whose project-relative path points at an existing text file. The file itself is
 * fetched through [WalkthroughRpcApi.resolveFiles]: a [VirtualFileId] is bound to the client session of
 * the RPC call that creates it, so it must be minted in a call made by the frontend that will open it.
 */
@Serializable
data class ResolvedItemDto(val item: WalkthroughItem, val lineCount: Int? = null)

/** Asks the frontend to navigate the popup to [index]. Honoured once per [seq]. */
@Serializable
data class FocusRequestDto(val seq: Long, val index: Int)

/**
 * Full snapshot of the active walkthrough session. The backend republishes it with a higher
 * [revision] on every change; the frontend reconciles idempotently on [sessionId] + [revision], so a
 * reconnect replay can't lose an insert or leave the question status stale.
 */
@Serializable
data class WalkthroughSessionStateDto(
    val sessionId: String,
    val revision: Long,
    val targetKind: WalkthroughTargetKind,
    val acceptsQuestions: Boolean,
    val diffDescriptors: List<DiffWalkthroughDescriptor>,
    val items: List<ResolvedItemDto>,
    val questionStatus: WalkthroughQuestionStatus,
    val loading: Boolean,
    val focusRequest: FocusRequestDto? = null,
)

/** Wraps the active session so the state flow never has to carry a bare `null`. */
@Serializable
data class WalkthroughUiStateDto(val active: WalkthroughSessionStateDto? = null)

@Serializable
sealed interface ShowResultDto {
    @Serializable
    data object Shown : ShowResultDto

    @Serializable
    data class NoEditor(val message: String) : ShowResultDto
}

@Serializable
sealed interface ReplayResultDto {
    @Serializable
    data object Shown : ReplayResultDto

    @Serializable
    data object NotFound : ReplayResultDto

    @Serializable
    data class NoEditor(val message: String) : ReplayResultDto

    @Serializable
    data object UiNotConnected : ReplayResultDto
}

@Serializable
data class DiffRevisionsDto(
    val leftText: String?,
    val rightText: String?,
    val leftPath: String,
    val rightPath: String,
    val title: String,
    val leftTitle: String,
    val rightTitle: String,
)

/** Exceptions don't cross the RPC boundary cleanly, so failures travel as values. */
@Serializable
sealed interface DiffRevisionsResultDto {
    @Serializable
    data class Loaded(val revisions: DiffRevisionsDto) : DiffRevisionsResultDto

    @Serializable
    data class Failed(val message: String) : DiffRevisionsResultDto
}

@Serializable
sealed interface ExportResultDto {
    @Serializable
    data class Written(val fileId: VirtualFileId) : ExportResultDto

    @Serializable
    data class Failed(val message: String) : ExportResultDto
}
