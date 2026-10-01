@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.ide.vfs.virtualFile
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.projectId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WalkthroughExportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val dataContext = event.dataContext
        showWalkthroughHistoryPopup(project, dataContext, "Export Walkthrough to Markdown") { record ->
            exportWalkthrough(project, record, dataContext)
        }
    }
}

/**
 * Renders [record] locally, asks where to save it, and has the backend write the file (the project
 * lives on the backend's filesystem). The written file is then opened in an editor.
 */
private fun exportWalkthrough(project: Project, record: WalkthroughRecord, dataContext: DataContext) {
    val markdown = renderWalkthroughMarkdown(record)
    val descriptor = FileSaverDescriptor(
        "Export Walkthrough to Markdown",
        "Save the selected walkthrough as a Markdown document",
        "md",
    )
    val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
    val baseDir = project.basePath?.let { basePath -> LocalFileSystem.getInstance().findFileByPath(basePath) }
    val defaultName = "${slugifyDescription(record.description).ifBlank { "walkthrough" }}.md"
    val wrapper = dialog.save(baseDir, defaultName) ?: return
    val path = wrapper.file.absolutePath

    FrontendWalkthroughHost.getInstance(project).scope.launch {
        val result = callBackend("export walkthrough") { writeExport(project.projectId(), path, markdown) }
        withContext(Dispatchers.EDT) {
            when (result) {
                is ExportResultDto.Written -> result.fileId.virtualFile()
                    ?.let { file -> FileEditorManager.getInstance(project).openFile(file, true) }

                is ExportResultDto.Failed ->
                    showPopupMessage("Failed to export walkthrough: ${result.message}", dataContext)

                null -> showPopupMessage("Failed to export walkthrough: the IDE backend did not respond", dataContext)
            }
        }
    }
}
