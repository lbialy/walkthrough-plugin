package com.forketyfork.walkthrough

import kotlinx.serialization.Serializable

@Serializable
enum class WalkthroughTargetKind {
    File,
    Diff,
}

@Serializable
enum class DiffSide {
    Left,
    Right,
}

@Serializable
data class DiffWalkthroughDescriptor(
    val id: String,
    val file: String? = null,
    val leftFile: String? = null,
    val rightFile: String? = null,
    val leftCommit: String,
    val rightCommit: String,
)

@Serializable
data class WalkthroughItem(
    val text: String,
    val file: String? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val diffId: String? = null,
    val diffFile: String? = null,
    val diffSide: DiffSide? = null,
    val label: String? = null,
    val parentLabel: String? = null,
)

fun assignTopLevelLabels(items: List<WalkthroughItem>): List<WalkthroughItem> = items.mapIndexed { index, item ->
    item.copy(label = item.label ?: (index + 1).toString(), parentLabel = null)
}
