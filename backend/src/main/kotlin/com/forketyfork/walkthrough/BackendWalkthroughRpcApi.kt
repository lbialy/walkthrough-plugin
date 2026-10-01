@file:Suppress("UnstableApiUsage")

package com.forketyfork.walkthrough

import com.intellij.ide.vfs.VirtualFileId
import com.intellij.ide.vfs.rpcId
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.nio.file.Path

internal class BackendWalkthroughRpcApi : WalkthroughRpcApi {
    // Returning a Flow from a suspend function is the fleet RPC contract for streams.
    @Suppress("SuspendFunWithFlowReturnType")
    override suspend fun sessionState(projectId: ProjectId): Flow<WalkthroughUiStateDto> {
        val project = projectId.findProjectOrNull() ?: return flowOf(WalkthroughUiStateDto())
        return WalkthroughBackendSessionRegistry.getInstance(project).state
    }

    override suspend fun reportShown(projectId: ProjectId, sessionId: String, result: ShowResultDto) {
        val project = projectId.findProjectOrNull() ?: return
        WalkthroughBackendSessionRegistry.getInstance(project).reportShown(sessionId, result)
    }

    override suspend fun submitQuestion(
        projectId: ProjectId,
        sessionId: String,
        question: String,
        parentLabel: String?,
    ) {
        val project = projectId.findProjectOrNull() ?: return
        WalkthroughBackendSessionRegistry.getInstance(project).get(sessionId)?.submitQuestion(question, parentLabel)
    }

    override suspend fun dismiss(projectId: ProjectId, sessionId: String) {
        val project = projectId.findProjectOrNull() ?: return
        WalkthroughBackendSessionRegistry.getInstance(project).remove(sessionId)
    }

    // Runs inside the frontend's RPC call, so rpcId() binds each file to that client's session.
    override suspend fun resolveFiles(projectId: ProjectId, paths: List<String>): Map<String, VirtualFileId> {
        val project = projectId.findProjectOrNull() ?: return emptyMap()
        return paths.distinct().mapNotNull { path ->
            WalkthroughSessionPublisher.findProjectFile(project, path)?.let { file -> path to file.rpcId() }
        }.toMap()
    }

    override suspend fun listHistory(projectId: ProjectId): List<WalkthroughRecord> {
        val project = projectId.findProjectOrNull() ?: return emptyList()
        return WalkthroughHistoryService.getInstance(project).list()
    }

    override suspend fun replayHistory(projectId: ProjectId, recordId: String): ReplayResultDto {
        val project = projectId.findProjectOrNull()
        val record = project?.let { WalkthroughHistoryService.getInstance(it).load(recordId) }
        if (project == null || record == null) return ReplayResultDto.NotFound
        val outcome = WalkthroughSessionPublisher.show(
            project = project,
            items = record.items,
            targetKind = record.targetKind,
            diffDescriptors = record.diffDescriptors,
            acceptsQuestions = false,
        )
        return when (outcome) {
            is ShowOutcome.Shown -> ReplayResultDto.Shown
            is ShowOutcome.NoEditor -> ReplayResultDto.NoEditor(outcome.message)
            ShowOutcome.UiNotConnected -> ReplayResultDto.UiNotConnected
        }
    }

    override suspend fun loadDiffRevisions(
        projectId: ProjectId,
        descriptor: DiffWalkthroughDescriptor,
    ): DiffRevisionsResultDto {
        val project = projectId.findProjectOrNull() ?: return DiffRevisionsResultDto.Failed("Project is not open")
        return DiffRevisionLoader.load(project, descriptor)
    }

    override suspend fun writeExport(projectId: ProjectId, path: String, markdown: String): ExportResultDto {
        val target = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
        val parentPath = target?.parent
        if (projectId.findProjectOrNull() == null || parentPath == null) {
            return ExportResultDto.Failed("Cannot export walkthrough to $path")
        }
        val fileName = target.fileName.toString()
        return try {
            val file = writeAction {
                val directory = VfsUtil.createDirectoryIfMissing(parentPath.toString())
                    ?: throw IOException("Cannot create directory $parentPath")
                val file = directory.findChild(fileName) ?: directory.createChildData(this, fileName)
                VfsUtil.saveText(file, markdown)
                file
            }
            ExportResultDto.Written(file.rpcId())
        } catch (exception: IOException) {
            LOG.warn("Failed to export walkthrough to Markdown", exception)
            ExportResultDto.Failed(exception.message ?: exception.javaClass.simpleName)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(BackendWalkthroughRpcApi::class.java)
    }
}
