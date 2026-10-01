package com.forketyfork.walkthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkthroughUiSessionTest {
    private val submitted = mutableListOf<Pair<String, String?>>()

    private fun state(
        revision: Long,
        labels: List<String> = listOf("1", "2", "3"),
        status: WalkthroughQuestionStatus = WalkthroughQuestionStatus.AgentNotWaiting,
        focus: FocusRequestDto? = FocusRequestDto(seq = 1, index = 0),
        sessionId: String = "s",
    ) = WalkthroughSessionStateDto(
        sessionId = sessionId,
        revision = revision,
        targetKind = WalkthroughTargetKind.File,
        acceptsQuestions = true,
        diffDescriptors = emptyList(),
        items = labels.map { ResolvedItemDto(WalkthroughItem(text = "text $it", label = it)) },
        questionStatus = status,
        loading = status == WalkthroughQuestionStatus.ProcessingQuestion,
        focusRequest = focus,
    )

    private fun newSession(initial: WalkthroughSessionStateDto = state(revision = 1)) =
        WalkthroughUiSession(initial) { question, parentLabel -> submitted += question to parentLabel }

    @Test
    fun sameRevisionIsNoOp() {
        val session = newSession()
        session.currentIndexState.intValue = 2

        val applied = session.apply(state(revision = 1, labels = listOf("x"), focus = FocusRequestDto(9, 0)))

        assertFalse(applied)
        assertEquals(listOf("1", "2", "3"), session.items.map { it.label })
        assertEquals(2, session.currentIndexState.intValue)
    }

    @Test
    fun otherSessionIsIgnored() {
        val session = newSession()

        assertFalse(session.apply(state(revision = 5, sessionId = "other")))
    }

    @Test
    fun newFocusSeqMovesIndex() {
        val session = newSession()

        session.apply(state(revision = 2, labels = listOf("1", "2", "2.1", "3"), focus = FocusRequestDto(2, 2)))

        assertEquals(2, session.currentIndexState.intValue)
        assertEquals("2.1", session.items[session.currentIndexState.intValue].label)
    }

    @Test
    fun repeatedFocusSeqDoesNotMoveIndexAgain() {
        val session = newSession()
        session.apply(state(revision = 2, focus = FocusRequestDto(2, 1)))
        session.currentIndexState.intValue = 2

        session.apply(
            state(revision = 3, status = WalkthroughQuestionStatus.WaitingForQuestion, focus = FocusRequestDto(2, 1)),
        )

        assertEquals(2, session.currentIndexState.intValue)
        assertEquals(WalkthroughQuestionStatus.WaitingForQuestion, session.questionStatusState.value)
    }

    @Test
    fun submitSendsCurrentLabelAndOptimisticStatusGetsOverwritten() {
        val session = newSession()
        session.currentIndexState.intValue = 1

        session.submitQuestion("  why?  ")

        assertEquals(listOf("why?" to "2"), submitted)
        assertEquals(WalkthroughQuestionStatus.QuestionQueued, session.questionStatusState.value)

        assertTrue(session.apply(state(revision = 2, status = WalkthroughQuestionStatus.ProcessingQuestion)))
        assertEquals(WalkthroughQuestionStatus.ProcessingQuestion, session.questionStatusState.value)
        assertEquals(true, session.loadingState.value)
    }

    @Test
    fun blankQuestionIsNotSent() {
        val session = newSession()

        session.submitQuestion("   ")

        assertTrue(submitted.isEmpty())
        assertEquals(WalkthroughQuestionStatus.AgentNotWaiting, session.questionStatusState.value)
    }

    @Test
    fun indexIsClampedWhenItemsShrink() {
        val session = newSession()
        session.currentIndexState.intValue = 2

        session.apply(state(revision = 2, labels = listOf("1")))

        assertEquals(0, session.currentIndexState.intValue)
    }
}
