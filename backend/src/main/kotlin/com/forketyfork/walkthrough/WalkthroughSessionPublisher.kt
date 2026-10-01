@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ShowOutcome {
    data class Shown(val session: WalkthroughBackendSession) : ShowOutcome
    data class NoEditor(val message: String) : ShowOutcome
    data object UiNotConnected : ShowOutcome
}

/**
 * Starts a walkthrough on the backend and waits until the frontend reports that it displayed it.
 * Shared by the MCP tools and history replay.
 */
object WalkthroughSessionPublisher {
    /** How long to wait for the frontend to acknowledge a new session before giving up. */
    const val SHOW_ACK_TIMEOUT_MILLIS: Long = 20_000L

    suspend fun show(
        project: Project,
        items: List<WalkthroughItem>,
        targetKind: WalkthroughTargetKind,
        diffDescriptors: List<DiffWalkthroughDescriptor>,
        acceptsQuestions: Boolean,
        ackTimeoutMillis: Long = SHOW_ACK_TIMEOUT_MILLIS,
    ): ShowOutcome {
        val resolved = resolveItems(project, items)
        return show(
            registry = WalkthroughBackendSessionRegistry.getInstance(project),
            items = resolved,
            targetKind = targetKind,
            diffDescriptors = diffDescriptors,
            acceptsQuestions = acceptsQuestions,
            ackTimeoutMillis = ackTimeoutMillis,
        )
    }

    @Suppress("LongParameterList")
    suspend fun show(
        registry: WalkthroughBackendSessionRegistry,
        items: List<ResolvedItemDto>,
        targetKind: WalkthroughTargetKind,
        diffDescriptors: List<DiffWalkthroughDescriptor>,
        acceptsQuestions: Boolean,
        ackTimeoutMillis: Long = SHOW_ACK_TIMEOUT_MILLIS,
    ): ShowOutcome {
        val start = registry.start(items, acceptsQuestions, targetKind, diffDescriptors)
        val sessionId = start.session.id
        var outcome: ShowOutcome? = null
        try {
            val result = withTimeoutOrNull(ackTimeoutMillis) { start.shown.await() }
            outcome = when (result) {
                null -> ShowOutcome.UiNotConnected
                ShowResultDto.Shown -> ShowOutcome.Shown(start.session)
                is ShowResultDto.NoEditor -> ShowOutcome.NoEditor(result.message)
            }
            return outcome
        } finally {
            // Also covers cancellation of the calling tool: the agent never learns this session id.
            if (outcome !is ShowOutcome.Shown) registry.discard(sessionId)
        }
    }

    /** Validates each item's project-relative file and records its line count for the frontend. */
    suspend fun resolveItems(project: Project, items: List<WalkthroughItem>): List<ResolvedItemDto> =
        items.map { item ->
            val file = item.file?.let { relativePath -> findProjectFile(project, relativePath) }
                ?: return@map ResolvedItemDto(item)
            readAction {
                ResolvedItemDto(item = item, lineCount = FileDocumentManager.getInstance().getDocument(file)?.lineCount)
            }
        }

    /** Finds the file at [relativePath] inside [project]; paths escaping the project resolve to `null`. */
    fun findProjectFile(project: Project, relativePath: String): VirtualFile? =
        resolveProjectRelativeWalkthroughPath(project.basePath, relativePath)
            ?.let { path -> LocalFileSystem.getInstance().findFileByPath(path.toString()) }
            ?.takeIf { virtualFile -> !virtualFile.isDirectory }
}
