package com.forketyfork.walkthrough

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WalkthroughProtocolSerializationTest {
    private val json = Json

    private val descriptor = DiffWalkthroughDescriptor(
        id = "d1",
        file = "src/Foo.kt",
        leftCommit = "abc",
        rightCommit = "def",
    )

    @Test
    fun sessionStateRoundTrips() {
        val state = WalkthroughSessionStateDto(
            sessionId = "session",
            revision = 7,
            targetKind = WalkthroughTargetKind.Diff,
            acceptsQuestions = true,
            diffDescriptors = listOf(descriptor),
            items = listOf(
                ResolvedItemDto(
                    item = WalkthroughItem(
                        text = "step",
                        line = 3,
                        endLine = 5,
                        diffId = "d1",
                        diffSide = DiffSide.Left,
                        label = "1.1",
                        parentLabel = "1",
                    ),
                    lineCount = 10,
                ),
            ),
            questionStatus = WalkthroughQuestionStatus.ProcessingQuestion,
            loading = true,
            focusRequest = FocusRequestDto(seq = 2, index = 0),
        )

        val decoded = json.decodeFromString(
            WalkthroughUiStateDto.serializer(),
            json.encodeToString(WalkthroughUiStateDto.serializer(), WalkthroughUiStateDto(state)),
        )

        assertEquals(WalkthroughUiStateDto(state), decoded)
    }

    @Test
    fun emptyUiStateRoundTrips() {
        val encoded = json.encodeToString(WalkthroughUiStateDto.serializer(), WalkthroughUiStateDto())
        assertEquals(WalkthroughUiStateDto(), json.decodeFromString(WalkthroughUiStateDto.serializer(), encoded))
    }

    @Test
    fun recordRoundTrips() {
        val record = WalkthroughRecord(
            id = "20260929-rec",
            createdAt = "2026-09-29T10:00:00Z",
            description = "Tour",
            targetKind = WalkthroughTargetKind.Diff,
            diffDescriptors = listOf(descriptor),
            items = listOf(WalkthroughItem(text = "a", label = "1")),
        )

        val decoded = json.decodeFromString(
            WalkthroughRecord.serializer(),
            json.encodeToString(WalkthroughRecord.serializer(), record),
        )

        assertEquals(record, decoded)
    }

    @Test
    fun diffRevisionsResultRoundTrips() {
        val loaded: DiffRevisionsResultDto = DiffRevisionsResultDto.Loaded(
            DiffRevisionsDto(
                leftText = null,
                rightText = "new",
                leftPath = "src/Foo.kt",
                rightPath = "src/Foo.kt",
                title = "src/Foo.kt: abc vs def",
                leftTitle = "abc",
                rightTitle = "def",
            ),
        )
        val failed: DiffRevisionsResultDto = DiffRevisionsResultDto.Failed("boom")

        listOf(loaded, failed).forEach { value ->
            val encoded = json.encodeToString(DiffRevisionsResultDto.serializer(), value)
            assertEquals(value, json.decodeFromString(DiffRevisionsResultDto.serializer(), encoded))
        }
    }

    @Test
    fun showAndReplayResultsRoundTrip() {
        listOf(ShowResultDto.Shown, ShowResultDto.NoEditor("No active editor")).forEach { value ->
            val encoded = json.encodeToString(ShowResultDto.serializer(), value)
            assertEquals(value, json.decodeFromString(ShowResultDto.serializer(), encoded))
        }
        listOf(
            ReplayResultDto.Shown,
            ReplayResultDto.NotFound,
            ReplayResultDto.NoEditor("x"),
            ReplayResultDto.UiNotConnected,
        ).forEach { value ->
            val encoded = json.encodeToString(ReplayResultDto.serializer(), value)
            assertEquals(value, json.decodeFromString(ReplayResultDto.serializer(), encoded))
        }
    }
}
