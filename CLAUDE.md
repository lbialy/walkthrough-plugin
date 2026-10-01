# CLAUDE.md (symlinked as AGENTS.md)

[Canonical file: `CLAUDE.md`. Keep `AGENTS.md` as a symlink to `CLAUDE.md`.]

## Project

**Name:** Walkthrough Plugin
**Description:** IntelliJ IDEA plugin for presenting inline walkthrough guidance inside the editor.
Shows styled popups near target lines with a connector anchored to the line, and lets the user
step through a sequence of walkthrough items. Built with JetBrains Compose via the Jewel library.
**Stack:** Kotlin 2.4.0, JetBrains Compose (Jewel), IntelliJ Platform Gradle Plugin v2 (split-mode
modules), fleet RPC (`rpc` Gradle plugin), kotlinx-serialization, Detekt
**Status:** Active development

## Build & Run

```bash
# Environment bootstrap
# Prerequisites: Nix (https://nixos.org/download), direnv (https://direnv.net)
direnv allow          # or: nix develop

# Build
just build            # or: ./gradlew buildPlugin

# Run in a sandboxed IDE instance (monolithic: frontend + backend modules in one JVM)
just run              # or: ./gradlew runIde

# Run in Split Mode: sandboxed IDE backend + JetBrains Client connected to it
just run-split        # or: ./gradlew runIdeSplitMode

# Verify plugin compatibility
just verify           # or: ./gradlew verifyPlugin

# Lint (everything CI runs: nix flake check + Detekt)
just lint             # or: nix flake check && ./gradlew detekt

# Run unit tests
just test             # or: ./gradlew test

# Clean
just clean            # or: ./gradlew clean

# Publish to JetBrains Marketplace
just publish          # or: ./gradlew publishPlugin

# Install pre-commit hooks (inside the Nix dev shell)
just hooks
```

The plugin is also tested by running it in the IDE via `runIde` and in Split Mode via
`runIdeSplitMode`. Split-mode run tasks don't accept `--args`; open the project from the JetBrains
Client window. The sandbox backend needs the MCP server enabled in its own config
(`.intellijPlatform/sandbox/.../config_runIdeBackend`).

## Infrastructure

- **Source code hosting:** GitHub — URL: `https://github.com/forketyfork/walkthrough-plugin` — Skill: `managing-github`
- **Issue tracker:** GitHub Issues — URL: `https://github.com/forketyfork/walkthrough-plugin/issues` — Skill: `managing-github`
- **CI/CD:** GitHub Actions — configs: `.github/workflows/build.yml`, `.github/workflows/release.yml`
- **Issue/PR linkage convention:** Reference issues in PR descriptions using `Closes #<number>` or
  `Fixes #<number>` to auto-close on merge. Include the issue number in the PR title as `(#<number>)`.

## Architecture

The plugin targets IntelliJ IDEA 262+ and is a Plugin Model v2 **split plugin**: one plugin zip,
installed on both the Host (backend) and the JetBrains Client (frontend). The root
`src/main/resources/META-INF/plugin.xml` only declares the plugin and its `<content>` modules; the
platform loads each module where its dependencies are satisfied. In a monolithic IDE all four
modules load in one JVM. Every module uses the package `com.forketyfork.walkthrough`; Kotlin
`internal` does not cross Gradle modules, so anything shared is `public`.

| Gradle module | Content module / descriptor | Loads where | Contents |
| --- | --- | --- | --- |
| `shared` | `walkthrough.shared` (required) | both | `WalkthroughItem`/`WalkthroughRecord` models, the `WalkthroughRpcApi` protocol + DTOs, pure helpers (labels, anchor fallback, Markdown export) |
| `backend` | `walkthrough.backend` | Host | session state machine, history store, Git revision loading, RPC implementation |
| `backend-mcp` | `walkthrough.backend.mcp` | Host with MCP Server enabled | `ShowWalkthroughItemsToolset` |
| `frontend` | `walkthrough.frontend` | Client | Compose/Jewel popup, editor overlay, diff viewer, settings, Tools menu actions |

MCP stays on the backend, pixels stay on the frontend, and they talk only through
`WalkthroughRpcApi` (fleet RPC; in a monolithic IDE the call is local).

### Protocol

- `sessionState(projectId): Flow<WalkthroughUiStateDto>` — a StateFlow snapshot of the active
  session (`null` = no popup), collected by the frontend in `durable {}`. Every change bumps
  `revision`; the frontend reconciles idempotently on `sessionId` + `revision`, and honours a
  `FocusRequestDto` once per `seq` (show → step 0, tangent insert → first inserted step).
- `reportShown` — the frontend acknowledges the first display of a session (`Shown`/`NoEditor`);
  the MCP tool waits for it (`SHOW_ACK_TIMEOUT_MILLIS`, 20 s) and otherwise fails with
  "Walkthrough UI did not respond…".
- `submitQuestion` (with the current step's `parentLabel`, which is frontend-owned), `dismiss`,
  `listHistory`, `replayHistory`, `writeExport`, `loadDiffRevisions`, `resolveFiles`.
- One-shot frontend calls go through `callBackend` (`durable` + 15 s timeout).

**Gotcha — `VirtualFileId`:** `rpcId()` binds the file to the client session of the *current RPC
call*. Minting ids inside an MCP tool call binds them to the Host's local session and
`virtualFile()` returns `null` on the JetBrains Client (it only works in a monolithic IDE because
the id carries the local file). So the backend only validates paths (`ResolvedItemDto.lineCount`)
and the frontend fetches ids itself via `resolveFiles`.

**Diffs are built on the frontend.** 262 has no public frontend diff extension, and a diff opened
by the backend is rendered on the backend. The backend returns revision texts
(`DiffRevisionLoader`, Git4Idea); the frontend builds a `SimpleDiffRequest`, calls
`DiffManager.showDiff`, and `WalkthroughDiffExtension` attaches the popup. Closing the diff tab
closes the popup (the viewer-disposal hook must be registered under the viewer only — a
`Disposable` has a single parent and `Disposer.register` re-parents).

### Key classes

- **Backend:** `WalkthroughBackendSession` (question waiter machine, grace period, tangent
  labels), `WalkthroughBackendSessionRegistry` (project service; one active session, dismissed-id
  ring buffer, state flow, pending show acks), `WalkthroughSessionPublisher` (resolve → start →
  await ack), `WalkthroughHistoryService`/`WalkthroughHistoryStore` (`.idea/walkthroughs/`, Gson),
  `DiffRevisionLoader`, `BackendWalkthroughRpcApi`.
- **Backend MCP:** `ShowWalkthroughItemsToolset` — `show_walkthrough_items`,
  `show_diff_walkthrough_items`, `await_walkthrough_question`, `insert_walkthrough_tangents`. Tool
  names, parameters and reply strings are part of the companion skill's contract; keep them stable.
- **Frontend:** `FrontendWalkthroughHost` (project service started by a `postStartupActivity`;
  collects the state and owns the popup), `WalkthroughUiSession` (Compose state + current step),
  `WalkthroughOrchestrator.kt` / `ResolvedWalkthroughTarget.kt` (file popups),
  `DiffWalkthroughSession.kt` (diff popups), `WalkthroughPopupSurface.kt` (layered-pane host and
  connector), `WalkthroughPopupContent.kt` / `WalkthroughPopupWidgets.kt` (Compose UI),
  `WalkthroughSettings*.kt` / `WalkthroughPalette.kt` (frontend-local settings),
  `WalkthroughHistoryAction` / `WalkthroughExportAction`.

### MCP server integration

`backend-mcp` depends on the bundled `com.intellij.mcpServer` plugin. Toolsets are registered in
`walkthrough.backend.mcp.xml` under `defaultExtensionNs="com.intellij.mcpServer"` with the
`<mcpToolset>` extension point. Tool methods are discovered by reflection: annotate a suspend
method with `@McpTool` and `@McpDescription`; annotate each parameter with `@McpDescription`. Use
`mcpFail(message)` to return an error response. Get the active project via
`currentCoroutineContext().projectOrNull` (import `com.intellij.mcpserver.projectOrNull`).

Current MCP flow:

1. `show_walkthrough_items(description, items)` shows labeled top-level steps and stores them in
   project history.
2. `await_walkthrough_question(walkthroughId)` waits for a user question from the active popup and
   returns the step label where it was asked.
3. `insert_walkthrough_tangents(walkthroughId, parentLabel, items)` inserts generated answer steps
   as labeled children and moves the popup to the first inserted step.

### Build notes

- `build.gradle.kts` resolves IntelliJ IDEA `intellijPlatformVersion` (`gradle.properties`) through
  the IntelliJ Platform Gradle Plugin; the root project assembles the four modules with
  `pluginModule(...)`, `splitMode = true` and `PluginInstallationTarget.BOTH`. `runIde` is pinned to
  `splitMode = false`.
- Plugin versions in `settings.gradle.kts` `pluginManagement` are literals (Gradle cannot read the
  version catalog there). Kotlin, the Compose compiler plugin and the serialization plugin must
  match the Kotlin version the `rpc` plugin is built for (`2.4.0-RC-0.1` → Kotlin 2.4.0).
- The platform loads content module `walkthrough.x` from `lib/modules/walkthrough.x.jar`, so each
  module's `composedJar` is renamed accordingly (`backend-mcp` → `walkthrough.backend.mcp`).
- `intellij.platform.ide.rpc` (home of `VirtualFileId`) is part of the core platform jar; don't
  declare it as a `bundledModule` or descriptor dependency.
- Detekt and JUnit 5 are configured for all modules in the root `subprojects {}` block.

## Agent Rules

1. Read this file before writing any code.
2. Follow existing coding conventions; do not introduce new patterns.
3. Use conventional commit messages.
4. Always implement complete, working code — never write stub comments instead of actual
   implementation.
5. Stay focused on the requested task — avoid scope creep or unrelated changes.
6. After making changes, verify the build and linting pass: `just lint && just build`
7. Do not introduce new dependencies without asking first.
8. Keep user-facing walkthrough UI in JetBrains Compose / Jewel. If Swing hosting glue is needed,
   keep it minimal and limited to integration layers such as popup surfaces or Compose bridges.
9. Keep all external dependency versions in `gradle/libs.versions.toml` (Gradle version catalog).
10. After completing a user-visible change, add a short entry to the `## [Unreleased]` section of
    `CHANGELOG.md` under `### Added`, `### Changed`, `### Fixed`, or `### Removed` as appropriate.
    Keep the entry user-focused (what changed from the user's perspective, not implementation
    detail) and include the related issue and/or PR number, e.g. `(#42)` or `(PR #42)`. Skip the
    entry for purely internal changes (refactors, test-only changes, CI tweaks) that a user would
    not notice.
