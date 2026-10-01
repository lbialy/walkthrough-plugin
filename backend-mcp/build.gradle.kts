dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.mcpServer")
    }

    implementation(project(":shared"))
    implementation(project(":backend"))
}
