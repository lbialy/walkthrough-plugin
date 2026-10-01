@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import androidx.compose.runtime.mutableStateOf
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffExtension
import com.intellij.diff.DiffManager
import com.intellij.diff.EditorDiffViewer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.ErrorDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.platform.project.projectId
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.lang.ref.WeakReference
import javax.swing.SwingUtilities
import com.intellij.diff.util.Side as PlatformDiffSide

private val DIFF_WALKTHROUGH_CONTROLLER_KEY: Key<DiffWalkthroughController> =
    Key.create("walkthrough.diff.controller")
private val DIFF_WALKTHROUGH_ITEM_KEY: Key<WalkthroughItem> =
    Key.create("walkthrough.diff.item")

/**
 * Shows the popup for a diff walkthrough [session] and opens the diff for its first item. The diff
 * viewer is built here on the frontend from revision texts the backend loads from Git, so it renders
 * locally and [WalkthroughDiffExtension] can attach the popup to it. Returns the disposable that owns
 * the popup, or `null` when the session has nothing to show. [onDismissed] runs once when the popup
 * goes away for any reason.
 */
@Suppress("LongMethod")
fun showDiffWalkthroughSession(
    project: Project,
    session: WalkthroughUiSession,
    coroutineScope: CoroutineScope,
    onDismissed: () -> Unit,
): Disposable? {
    val descriptors = session.diffDescriptors
    val firstItem = session.items.firstOrNull()
    if (descriptors.isEmpty() || firstItem == null) return null

    val paletteState = mutableStateOf(WalkthroughSettings.getInstance().selectedPalette)
    val sessionDisposable = Disposer.newCheckedDisposable("DiffWalkthroughPopupSession")
    Disposer.register(project, sessionDisposable)
    Disposer.register(sessionDisposable, onDismissed)
    val sessionScope = coroutineScope.childScope("DiffWalkthroughSession")
    Disposer.register(sessionDisposable) { sessionScope.cancel() }

    var popupRef: WalkthroughPopupSurface? = null
    lateinit var controller: DiffWalkthroughController

    fun updatePopupPalette(palette: WalkthroughPalette) {
        SwingUtilities.invokeLater {
            if (sessionDisposable.isDisposed) {
                return@invokeLater
            }
            paletteState.value = palette
            popupRef?.updatePalette(palette)
        }
    }

    controller = DiffWalkthroughController(
        project = project,
        descriptors = descriptors,
        sessionDisposable = sessionDisposable,
        sessionScope = sessionScope,
        popupProvider = { popupRef },
    )

    val panel = createWalkthroughPanel(
        project = project,
        session = session,
        paletteProvider = { paletteState.value },
        onItemDisplayed = controller::scheduleItemNavigation,
        onNavigateToSource = controller::scheduleItemNavigation,
        onClose = { popupRef?.cancel() },
    )
    makeComponentHierarchyTransparent(panel)

    ApplicationManager.getApplication().messageBus.connect(sessionDisposable).subscribe(
        WalkthroughSettingsListener.TOPIC,
        object : WalkthroughSettingsListener {
            override fun paletteChanged(palette: WalkthroughPalette) {
                updatePopupPalette(palette)
            }
        },
    )

    val popup = WalkthroughPopupSurface(
        content = panel,
        palette = paletteState.value,
        onCloseRequested = {
            popupRef = null
            Disposer.dispose(sessionDisposable)
        },
        onInteractionEnd = { saveCurrentGeometry(popupRef) },
    )
    popupRef = popup
    Disposer.register(sessionDisposable, popup)
    controller.scheduleItemNavigation(firstItem)
    return sessionDisposable
}

class WalkthroughDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val controller = request.getUserData(DIFF_WALKTHROUGH_CONTROLLER_KEY) ?: return
        val item = request.getUserData(DIFF_WALKTHROUGH_ITEM_KEY) ?: return
        controller.attachToViewer(viewer, item)
    }
}

private class DiffWalkthroughController(
    private val project: Project,
    private val descriptors: List<DiffWalkthroughDescriptor>,
    private val sessionDisposable: CheckedDisposable,
    private val sessionScope: CoroutineScope,
    private val popupProvider: () -> WalkthroughPopupSurface?,
) {
    private var pendingNavigationId = 0
    private var activeViewer: FrameDiffTool.DiffViewer? = null
    private var activeDescriptorId: String? = null
    private val revisionsCache = HashMap<String, DiffRevisionsDto>()
    private var loadJob: Job? = null

    fun scheduleItemNavigation(item: WalkthroughItem) {
        pendingNavigationId += 1
        val navigationId = pendingNavigationId
        SwingUtilities.invokeLater {
            if (sessionDisposable.isDisposed || navigationId != pendingNavigationId) {
                return@invokeLater
            }
            showItem(item)
        }
    }

    fun attachToViewer(viewer: FrameDiffTool.DiffViewer, item: WalkthroughItem) {
        if (sessionDisposable.isDisposed || viewer !is EditorDiffViewer) return
        trackActiveViewer(viewer, resolveDescriptor(item)?.id)
        val editor = selectEditor(viewer, item.diffSide ?: DiffSide.Right)
        val popup = popupProvider()
        if (editor != null && popup != null) {
            attachWhenShowing(popup, editor, item)
        }
    }

    private fun trackActiveViewer(viewer: FrameDiffTool.DiffViewer, descriptorId: String?) {
        activeDescriptorId = descriptorId
        if (activeViewer === viewer) return
        activeViewer = viewer
        // Avoid capturing `this` strongly inside the viewer-owned disposable: the diff viewer may
        // outlive the walkthrough session, and a strong reference would keep the whole controller
        // graph (session disposable, popup state, etc.) reachable until the diff tab is closed.
        // Register it under the viewer only: a Disposable has a single parent, and registering it
        // under the session as well would move it there, so closing the diff tab would never reach it.
        Disposer.register(viewer, createActiveViewerCleanup(WeakReference(this), viewer))
    }

    private fun attachWhenShowing(popup: WalkthroughPopupSurface, editor: Editor, item: WalkthroughItem) {
        val component = editor.contentComponent
        if (component.isShowing) {
            attachPopupToEditor(popup, editor, item)
            return
        }
        val listener = object : HierarchyListener {
            override fun hierarchyChanged(event: HierarchyEvent) {
                val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
                if (showingChanged && component.isShowing) {
                    component.removeHierarchyListener(this)
                    if (!sessionDisposable.isDisposed) {
                        attachPopupToEditor(popup, editor, item)
                    }
                }
            }
        }
        component.addHierarchyListener(listener)
        Disposer.register(
            sessionDisposable,
            Disposable { component.removeHierarchyListener(listener) },
        )
    }

    private fun attachPopupToEditor(popup: WalkthroughPopupSurface, editor: Editor, item: WalkthroughItem) {
        val popupItem = if (isResolvableWalkthroughLine(item.line, editor.document.lineCount)) {
            moveEditorCaretToLine(editor, item.line)
            item.withResolvedEndLine(editor.document.lineCount)
        } else {
            item.copy(line = null, endLine = null)
        }
        popup.update(editor, popupItem)
        popup.connectorHidden = false
        applyPopupGeometryForItem(popup, editor, popupItem)
    }

    // Called from [createActiveViewerCleanup] via `WeakReference<DiffWalkthroughController>.get()?.…`.
    // Qodana's UnusedSymbol inspection can't resolve the call target through the weak reference
    // (the generic is erased to `Object?`), so it flags the function as unused — suppress here.
    @Suppress("unused")
    fun clearActiveViewerIfMatches(viewer: FrameDiffTool.DiffViewer) {
        if (activeViewer !== viewer) return
        activeViewer = null
        activeDescriptorId = null
        // The popup belongs to the diff tab it is anchored in, so closing that tab ends the
        // walkthrough. A viewer is also disposed when the diff is re-rendered in the same tab (e.g.
        // switching side-by-side/unified); the replacement attaches synchronously, so check one EDT
        // turn later and keep the popup if a new viewer attached or a diff is being opened.
        SwingUtilities.invokeLater {
            val viewerReplaced = activeViewer != null || loadJob?.isActive == true
            if (!sessionDisposable.isDisposed && !viewerReplaced) {
                popupProvider()?.cancel()
            }
        }
    }

    private fun showItem(item: WalkthroughItem) {
        val descriptor = resolveDescriptor(item) ?: return
        val existing = activeViewer
        val cached = revisionsCache[descriptor.id]
        when {
            existing != null && !existing.isViewerDisposed() && activeDescriptorId == descriptor.id ->
                attachToViewer(existing, item)

            cached != null -> showWalkthroughDiff(project, cached, item, this)

            else -> loadAndShowDiff(descriptor, item)
        }
    }

    private fun loadAndShowDiff(descriptor: DiffWalkthroughDescriptor, item: WalkthroughItem) {
        val navigationId = pendingNavigationId
        loadJob?.cancel()
        loadJob = sessionScope.launch {
            val result = callBackend("load diff revisions") { loadDiffRevisions(project.projectId(), descriptor) }
                ?: DiffRevisionsResultDto.Failed("Timed out loading revisions for ${descriptor.displayFile}")
            withContext(Dispatchers.EDT) {
                if (sessionDisposable.isDisposed || navigationId != pendingNavigationId) return@withContext
                when (result) {
                    is DiffRevisionsResultDto.Loaded -> {
                        revisionsCache[descriptor.id] = result.revisions
                        showWalkthroughDiff(project, result.revisions, item, this@DiffWalkthroughController)
                    }

                    is DiffRevisionsResultDto.Failed -> showDiffError(project, descriptor, result.message)
                }
            }
        }
    }

    private fun resolveDescriptor(item: WalkthroughItem): DiffWalkthroughDescriptor? = item.diffId
        ?.let { id -> descriptors.firstOrNull { descriptor -> descriptor.id == id } }
        ?: item.diffFile?.let(::resolveDescriptorByFile)
        ?: descriptors.singleOrNull()

    private fun resolveDescriptorByFile(diffFile: String): DiffWalkthroughDescriptor? = descriptors
        .singleOrNull { descriptor ->
            diffFile == descriptor.file || diffFile == descriptor.leftFile || diffFile == descriptor.rightFile
        }

    private fun selectEditor(viewer: EditorDiffViewer, side: DiffSide): Editor? {
        val editors = viewer.editors
        return when (side) {
            DiffSide.Left -> editors.getOrNull(0)
            DiffSide.Right -> editors.getOrNull(1) ?: editors.getOrNull(0)
        }
    }
}

/**
 * Opens a diff viewer for [revisions] on this frontend, tagged so [WalkthroughDiffExtension] attaches
 * the walkthrough popup of [controller] to it once the viewer exists.
 */
private fun showWalkthroughDiff(
    project: Project,
    revisions: DiffRevisionsDto,
    item: WalkthroughItem,
    controller: DiffWalkthroughController,
) {
    val request = SimpleDiffRequest(
        revisions.title,
        revisionContent(project, revisions.leftText, revisions.leftPath),
        revisionContent(project, revisions.rightText, revisions.rightPath),
        revisions.leftTitle,
        revisions.rightTitle,
    ).apply {
        putUserData(DIFF_WALKTHROUGH_CONTROLLER_KEY, controller)
        putUserData(DIFF_WALKTHROUGH_ITEM_KEY, item)
        item.line?.let { line ->
            putUserData(
                DiffUserDataKeys.SCROLL_TO_LINE,
                Pair.create(item.diffSide.toPlatformSide(), (line - 1).coerceAtLeast(0)),
            )
        }
        putUserData(DiffUserDataKeys.MASTER_SIDE, item.diffSide.toPlatformSide())
    }
    DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(request), DiffDialogHints.DEFAULT)
}

private fun revisionContent(project: Project, text: String?, path: String): DiffContent {
    val contentFactory = DiffContentFactory.getInstance()
    if (text == null) return contentFactory.createEmpty()
    val fileName = path.substringAfterLast('/')
    return contentFactory.create(project, text, FileTypeManager.getInstance().getFileTypeByFileName(fileName))
}

private fun showDiffError(project: Project, descriptor: DiffWalkthroughDescriptor, message: String) {
    DiffManager.getInstance().showDiff(
        project,
        SimpleDiffRequestChain(ErrorDiffRequest(descriptor.displayFile, message)),
        DiffDialogHints.DEFAULT,
    )
}

/**
 * Creates a [Disposable] (to be registered under [viewer]) that tells the controller when [viewer] is
 * disposed, e.g. because its diff tab was closed. The controller is held weakly so a still-open diff
 * viewer cannot keep the walkthrough session graph reachable after the session itself has been closed.
 */
private fun createActiveViewerCleanup(
    controllerRef: WeakReference<DiffWalkthroughController>,
    viewer: FrameDiffTool.DiffViewer,
): Disposable = Disposable {
    controllerRef.get()?.clearActiveViewerIfMatches(viewer)
    controllerRef.clear()
}

/**
 * Non-deprecated replacement for `Disposer.isDisposed(disposable)`: relies on [CheckedDisposable]
 * when the viewer implements it. If the viewer doesn't expose its disposal state we conservatively
 * treat it as not disposed; in that case the viewer-owned cleanup callback above will clear
 * `activeViewer` once the platform actually disposes the viewer.
 */
private fun FrameDiffTool.DiffViewer.isViewerDisposed(): Boolean = (this as? CheckedDisposable)?.isDisposed == true

private val DiffWalkthroughDescriptor.displayFile: String
    get() = rightFile ?: file ?: leftFile ?: id

private fun DiffSide?.toPlatformSide(): PlatformDiffSide = when (this) {
    DiffSide.Left -> PlatformDiffSide.LEFT
    DiffSide.Right, null -> PlatformDiffSide.RIGHT
}
