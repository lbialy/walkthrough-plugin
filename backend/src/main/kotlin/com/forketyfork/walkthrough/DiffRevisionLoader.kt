package com.forketyfork.walkthrough

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SHORT_COMMIT_LENGTH = 12

/**
 * Loads both sides of a diff walkthrough file from Git on the backend. The frontend builds and shows
 * the diff viewer from the returned texts, so the diff is rendered where the user is.
 */
object DiffRevisionLoader {
    private val LOG = Logger.getInstance(DiffRevisionLoader::class.java)

    suspend fun load(
        project: Project,
        descriptor: DiffWalkthroughDescriptor,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): DiffRevisionsResultDto = withContext(ioDispatcher) {
        try {
            val leftRelativePath = descriptor.leftFile ?: descriptor.file ?: descriptor.rightFile
            val rightRelativePath = descriptor.rightFile ?: descriptor.file ?: descriptor.leftFile
            val leftText = loadRevisionText(
                project,
                resolveDiffFilePath(project, leftRelativePath),
                descriptor.leftCommit,
            )
            val rightText =
                loadRevisionText(project, resolveDiffFilePath(project, rightRelativePath), descriptor.rightCommit)
            if (leftText == null && rightText == null) {
                DiffRevisionsResultDto.Failed("Failed to load both revisions for ${descriptor.displayFile}")
            } else {
                DiffRevisionsResultDto.Loaded(
                    DiffRevisionsDto(
                        leftText = leftText,
                        rightText = rightText,
                        leftPath = requireNotNull(leftRelativePath),
                        rightPath = requireNotNull(rightRelativePath),
                        title = descriptor.displayTitle,
                        leftTitle = descriptor.leftCommit.shortCommit(),
                        rightTitle = descriptor.rightCommit.shortCommit(),
                    ),
                )
            }
        } catch (exception: IllegalArgumentException) {
            DiffRevisionsResultDto.Failed(exception.message ?: "Invalid diff descriptor ${descriptor.id}")
        }
    }

    private fun loadRevisionText(project: Project, filePath: FilePath, commit: String): String? = try {
        GitContentRevision.createRevision(filePath, GitRevisionNumber(commit), project).content
    } catch (exception: VcsException) {
        LOG.info("Failed to load ${filePath.path} at $commit", exception)
        null
    }

    private fun resolveDiffFilePath(project: Project, relativePath: String?): FilePath {
        require(!relativePath.isNullOrBlank()) { "Diff file path must not be blank" }
        val absolutePath = resolveProjectRelativeWalkthroughPath(project.basePath, relativePath)
            ?: throw IllegalArgumentException("Invalid project-relative diff path: $relativePath")
        return VcsUtil.getFilePath(absolutePath.toString(), false)
    }
}

private val DiffWalkthroughDescriptor.displayFile: String
    get() = rightFile ?: file ?: leftFile ?: id

private val DiffWalkthroughDescriptor.displayTitle: String
    get() = "$displayFile: ${leftCommit.shortCommit()} vs ${rightCommit.shortCommit()}"

private fun String.shortCommit(): String = take(SHORT_COMMIT_LENGTH)
