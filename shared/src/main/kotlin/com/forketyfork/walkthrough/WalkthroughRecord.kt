package com.forketyfork.walkthrough

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class WalkthroughRecord(
    val id: String,
    val createdAt: String,
    val description: String,
    val targetKind: WalkthroughTargetKind = WalkthroughTargetKind.File,
    val diffDescriptors: List<DiffWalkthroughDescriptor> = emptyList(),
    val items: List<WalkthroughItem>,
)

fun WalkthroughRecord.createdAtInstantOrEpoch(): Instant =
    runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH)
