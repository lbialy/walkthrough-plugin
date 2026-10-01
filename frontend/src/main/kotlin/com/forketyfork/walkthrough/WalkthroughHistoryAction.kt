@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.project.projectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val NO_EDITOR_FOR_REPLAY_MESSAGE = "No active editor for walkthrough replay"
private const val BACKEND_UNAVAILABLE_MESSAGE = "Walkthrough backend did not respond"

class WalkthroughHistoryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val dataContext = event.dataContext
        showWalkthroughHistoryPopup(project, dataContext, "Walkthrough History") { record ->
            replayWalkthrough(project, record, dataContext)
        }
    }
}

/**
 * Replays [record] through the backend, which resolves the files and publishes a new session that
 * the frontend host then shows like any other. Replays don't accept questions and aren't re-saved.
 */
private fun replayWalkthrough(project: Project, record: WalkthroughRecord, dataContext: DataContext) {
    FrontendWalkthroughHost.getInstance(project).scope.launch {
        val result = callBackend("replay walkthrough history") { replayHistory(project.projectId(), record.id) }
        val message = when (result) {
            ReplayResultDto.Shown -> null
            ReplayResultDto.NotFound -> "Walkthrough history record no longer exists"
            is ReplayResultDto.NoEditor, ReplayResultDto.UiNotConnected, null -> NO_EDITOR_FOR_REPLAY_MESSAGE
        }
        if (message != null) {
            withContext(Dispatchers.EDT) { showPopupMessage(message, dataContext) }
        }
    }
}

/**
 * Shows the saved walkthroughs for [project] in a searchable list popup. Selecting an entry invokes
 * [onSelect] (on the EDT) with the chosen record. Shared by the replay and Markdown-export actions so
 * both expose the same history list. The list is fetched from the backend first.
 */
internal fun showWalkthroughHistoryPopup(
    project: Project,
    dataContext: DataContext,
    title: String,
    onSelect: (WalkthroughRecord) -> Unit,
) {
    FrontendWalkthroughHost.getInstance(project).scope.launch {
        val records = callBackend("list walkthrough history") { listHistory(project.projectId()) }
        withContext(Dispatchers.EDT) {
            when {
                records == null -> showPopupMessage(BACKEND_UNAVAILABLE_MESSAGE, dataContext)
                records.isEmpty() -> showPopupMessage("No walkthrough history for this project", dataContext)
                else -> showHistoryList(records, title, dataContext, onSelect)
            }
        }
    }
}

private fun showHistoryList(
    records: List<WalkthroughRecord>,
    title: String,
    dataContext: DataContext,
    onSelect: (WalkthroughRecord) -> Unit,
) {
    val actionGroup = DefaultActionGroup().apply {
        records.forEach { record ->
            add(SelectWalkthroughAction(record, onSelect))
        }
    }
    JBPopupFactory.getInstance()
        .createActionGroupPopup(
            title,
            actionGroup,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        .showInBestPositionFor(dataContext)
}

internal fun showPopupMessage(message: String, dataContext: DataContext) {
    JBPopupFactory.getInstance()
        .createMessage(message)
        .showInBestPositionFor(dataContext)
}

private class SelectWalkthroughAction(
    private val record: WalkthroughRecord,
    private val onSelect: (WalkthroughRecord) -> Unit,
) : AnAction(formatRecord(record)) {
    override fun actionPerformed(event: AnActionEvent) {
        onSelect(record)
    }
}

private fun formatRecord(record: WalkthroughRecord): String {
    val timestamp = recordDisplayFormatter.format(record.createdAtInstantOrEpoch())
    return "$timestamp - ${record.description}"
}

private val recordDisplayFormatter = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
