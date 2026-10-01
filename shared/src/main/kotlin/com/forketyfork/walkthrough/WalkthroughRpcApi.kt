@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * The only channel between the backend (MCP tools, history, Git) and the frontend (popup, editor
 * overlay, diff viewer). In a monolithic IDE both ends run in the same JVM and the platform
 * short-circuits the transport.
 */
@Rpc
interface WalkthroughRpcApi : RemoteApi<Unit> {
    /** State of the active session; `active == null` means no popup should be shown. */
    // Returning a Flow from a suspend function is the fleet RPC contract for streams.
    @Suppress("SuspendFunWithFlowReturnType")
    suspend fun sessionState(projectId: ProjectId): Flow<WalkthroughUiStateDto>

    /** Acknowledges the first display of [sessionId]. The first report for a session wins. */
    suspend fun reportShown(projectId: ProjectId, sessionId: String, result: ShowResultDto)

    suspend fun submitQuestion(projectId: ProjectId, sessionId: String, question: String, parentLabel: String?)

    suspend fun dismiss(projectId: ProjectId, sessionId: String)

    /**
     * Resolves project-relative [paths] to files the calling frontend can open. Paths that don't
     * point at an existing file inside the project are left out of the result.
     */
    suspend fun resolveFiles(projectId: ProjectId, paths: List<String>): Map<String, VirtualFileId>

    suspend fun listHistory(projectId: ProjectId): List<WalkthroughRecord>

    suspend fun replayHistory(projectId: ProjectId, recordId: String): ReplayResultDto

    /** Loads both revisions of a diff walkthrough file; fails if neither side could be loaded. */
    suspend fun loadDiffRevisions(projectId: ProjectId, descriptor: DiffWalkthroughDescriptor): DiffRevisionsResultDto

    suspend fun writeExport(projectId: ProjectId, path: String, markdown: String): ExportResultDto

    companion object {
        suspend fun getInstance(): WalkthroughRpcApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<WalkthroughRpcApi>())
    }
}
