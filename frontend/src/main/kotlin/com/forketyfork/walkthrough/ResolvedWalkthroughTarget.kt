package com.forketyfork.walkthrough

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal data class ResolvedWalkthroughTarget(val editor: Editor, val popupItem: WalkthroughItem)

/**
 * Picks the editor and anchor for [item]. [resolved] carries the backend-validated line count and
 * [file] the frontend file the backend resolved for the item's path; when either is missing the
 * popup falls back to the current editor and caret.
 */
internal fun resolveWalkthroughTarget(
    project: Project,
    fallbackEditor: Editor?,
    item: WalkthroughItem,
    resolved: ResolvedItemDto?,
    file: VirtualFile?,
): ResolvedWalkthroughTarget? {
    val fileEditorManager = FileEditorManager.getInstance(project)
    val fileTarget = item.file
        ?.let { resolveFileTarget(project, fileEditorManager, item, resolved?.lineCount, file) }
    val fallbackItem = if (item.file != null && fileTarget == null) item.withFallbackAnchor() else item
    return fileTarget ?: resolveFallbackTarget(fileEditorManager, fallbackEditor, fallbackItem)
}

private fun resolveFallbackTarget(
    fileEditorManager: FileEditorManager,
    fallbackEditor: Editor?,
    item: WalkthroughItem,
): ResolvedWalkthroughTarget? = run {
    val editor = fileEditorManager.selectedTextEditor ?: fallbackEditor ?: return@run null
    if (!isResolvableWalkthroughLine(item.line, editor.document.lineCount)) {
        return@run ResolvedWalkthroughTarget(editor, item.withFallbackAnchor())
    }
    val resolvedItem = item.withResolvedEndLine(editor.document.lineCount)
    moveEditorCaretToLine(editor, resolvedItem.line)
    ResolvedWalkthroughTarget(editor, resolvedItem)
}

private fun resolveFileTarget(
    project: Project,
    fileEditorManager: FileEditorManager,
    item: WalkthroughItem,
    lineCount: Int?,
    file: VirtualFile?,
): ResolvedWalkthroughTarget? {
    // The backend resolved the project-relative path; the frontend never touches its filesystem.
    val resolvedItem = lineCount
        ?.takeIf { isResolvableWalkthroughLine(item.line, it) }
        ?.let { item.withResolvedEndLine(it) }
    val editor = if (file != null && resolvedItem != null) {
        openEditor(project, fileEditorManager, file, resolvedItem)
    } else {
        null
    }
    if (editor == null || resolvedItem == null) {
        LOG.info(
            "Cannot anchor walkthrough item at ${item.file}:${item.line} (file=$file, lineCount=$lineCount, " +
                "editorOpened=${editor != null}); falling back to the current caret",
        )
        return null
    }
    return ResolvedWalkthroughTarget(editor, resolvedItem)
}

private fun openEditor(
    project: Project,
    fileEditorManager: FileEditorManager,
    virtualFile: VirtualFile,
    item: WalkthroughItem,
): Editor? {
    val lineIndex = (item.line ?: 1).coerceAtLeast(1) - 1
    return runCatching {
        fileEditorManager.openTextEditor(OpenFileDescriptor(project, virtualFile, lineIndex, 0), true)
    }.getOrNull()?.also { editor -> moveEditorCaretToLine(editor, item.line) }
}

private val LOG = Logger.getInstance(ResolvedWalkthroughTarget::class.java)
