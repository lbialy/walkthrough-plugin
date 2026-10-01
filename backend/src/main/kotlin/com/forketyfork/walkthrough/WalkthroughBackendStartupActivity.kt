package com.forketyfork.walkthrough

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class WalkthroughBackendStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        LOG.info("Walkthrough backend module loaded for project ${project.name}")
    }

    private companion object {
        private val LOG = Logger.getInstance(WalkthroughBackendStartupActivity::class.java)
    }
}
