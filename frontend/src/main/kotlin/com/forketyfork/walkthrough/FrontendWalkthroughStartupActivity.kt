package com.forketyfork.walkthrough

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts mirroring the backend's walkthrough sessions as soon as the project opens on this frontend. */
internal class FrontendWalkthroughStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        FrontendWalkthroughHost.getInstance(project).start()
    }
}
