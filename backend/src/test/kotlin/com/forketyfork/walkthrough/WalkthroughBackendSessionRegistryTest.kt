package com.forketyfork.walkthrough

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkthroughBackendSessionRegistryTest {
    private fun newRegistry() = WalkthroughBackendSessionRegistry().apply { notListeningGracePeriodMillis = 0L }

    private fun items(vararg texts: String): List<ResolvedItemDto> =
        assignTopLevelLabels(texts.map { WalkthroughItem(text = it) }).map { ResolvedItemDto(it) }

    @Test
    fun startPublishesStateWithInitialFocus() {
        val registry = newRegistry()

        val start = registry.start(items("one", "two"), acceptsQuestions = true)

        val state = registry.state.value.active!!
        assertEquals(start.session.id, state.sessionId)
        assertEquals(listOf("1", "2"), state.items.map { it.item.label })
        assertEquals(0, state.focusRequest?.index)
        assertEquals(WalkthroughQuestionStatus.AgentNotWaiting, state.questionStatus)
    }

    @Test
    fun insertTangentsBumpsRevisionAndFocusSeq() {
        val registry = newRegistry()
        val session = registry.start(items("one", "two", "three"), acceptsQuestions = true).session
        val before = registry.state.value.active!!

        registry.insertTangents(session, "2", listOf(ResolvedItemDto(WalkthroughItem(text = "answer"))))

        val after = registry.state.value.active!!
        assertTrue(after.revision > before.revision)
        assertTrue(after.focusRequest!!.seq > before.focusRequest!!.seq)
        assertEquals(2, after.focusRequest!!.index)
        assertEquals(listOf("1", "2", "2.1", "3"), after.items.map { it.item.label })
    }

    @Test
    fun questionStatusChangesArePublished() {
        val registry = newRegistry()
        val session = registry.start(items("one"), acceptsQuestions = true).session

        session.submitQuestion("why?", "1")

        assertEquals(WalkthroughQuestionStatus.QuestionQueued, registry.state.value.active?.questionStatus)
    }

    @Test
    fun rejectedQuestionStillRepublishesSoOptimisticStatusIsCorrected() {
        val registry = newRegistry()
        val session = registry.start(items("one"), acceptsQuestions = true).session
        session.submitQuestion("first", "1")
        val revision = registry.state.value.active!!.revision

        // A second question while one is queued is rejected; the status must still be republished.
        session.submitQuestion("second", "1")

        val state = registry.state.value.active!!
        assertTrue(state.revision > revision)
        assertEquals(WalkthroughQuestionStatus.QuestionQueued, state.questionStatus)
    }

    @Test
    fun removePublishesNullAndDismissesPendingAwait() = runBlocking {
        val registry = newRegistry()
        val session = registry.start(items("one"), acceptsQuestions = true).session
        val await = async { session.awaitQuestionResult(timeoutMillis = 5_000L) }
        while (session.questionStatus != WalkthroughQuestionStatus.WaitingForQuestion) yield()

        registry.remove(session.id)

        assertSame(WalkthroughQuestionAwaitResult.Dismissed, await.await())
        assertNull(registry.state.value.active)
        assertNull(registry.get(session.id))
    }

    @Test
    fun removeKeepsDismissedSessionQueryableUntilConsumed() {
        val registry = newRegistry()
        val session = registry.start(items("only"), acceptsQuestions = true).session

        registry.remove(session.id)

        assertNull(registry.get(session.id))
        assertEquals(true, registry.consumeDismissed(session.id))
        assertEquals(false, registry.consumeDismissed(session.id))
    }

    @Test
    fun startingNewSessionDismissesPrevious() {
        val registry = newRegistry()
        val first = registry.start(items("one"), acceptsQuestions = true).session

        val second = registry.start(items("two"), acceptsQuestions = true).session

        assertTrue(first.isDismissed)
        assertNull(registry.get(first.id))
        assertTrue(registry.consumeDismissed(first.id))
        assertEquals(second.id, registry.state.value.active?.sessionId)
    }

    @Test
    fun changesOfReplacedSessionAreNotPublished() {
        val registry = newRegistry()
        val first = registry.start(items("one"), acceptsQuestions = true).session
        registry.start(items("two"), acceptsQuestions = true)
        val state = registry.state.value

        first.submitQuestion("late", "1")

        assertSame(state, registry.state.value)
    }

    @Test
    fun firstShowReportWins() = runBlocking {
        val registry = newRegistry()
        val start = registry.start(items("one"), acceptsQuestions = true)

        registry.reportShown(start.session.id, ShowResultDto.Shown)
        registry.reportShown(start.session.id, ShowResultDto.NoEditor("late"))

        assertSame(ShowResultDto.Shown, start.shown.await())
    }

    @Test
    fun publisherReturnsShownOnAck() = runBlocking {
        val registry = newRegistry()
        val outcome = async {
            WalkthroughSessionPublisher.show(
                registry = registry,
                items = items("one"),
                targetKind = WalkthroughTargetKind.File,
                diffDescriptors = emptyList(),
                acceptsQuestions = true,
            )
        }
        while (registry.state.value.active == null) yield()
        registry.reportShown(registry.state.value.active!!.sessionId, ShowResultDto.Shown)

        val result = outcome.await()
        assertTrue(result is ShowOutcome.Shown)
        assertNotNull(registry.get((result as ShowOutcome.Shown).session.id))
    }

    @Test
    fun publisherAckTimeoutReportsUiNotConnectedAndDropsSession() = runBlocking {
        val registry = newRegistry()

        val outcome = WalkthroughSessionPublisher.show(
            registry = registry,
            items = items("one"),
            targetKind = WalkthroughTargetKind.File,
            diffDescriptors = emptyList(),
            acceptsQuestions = true,
            ackTimeoutMillis = 10L,
        )

        assertSame(ShowOutcome.UiNotConnected, outcome)
        assertNull(registry.state.value.active)
    }

    @Test
    fun publisherNoEditorDropsSession() = runBlocking {
        val registry = newRegistry()
        val outcome = async {
            WalkthroughSessionPublisher.show(
                registry = registry,
                items = items("one"),
                targetKind = WalkthroughTargetKind.File,
                diffDescriptors = emptyList(),
                acceptsQuestions = true,
            )
        }
        while (registry.state.value.active == null) yield()
        val sessionId = registry.state.value.active!!.sessionId
        registry.reportShown(sessionId, ShowResultDto.NoEditor("No active editor"))

        assertEquals(ShowOutcome.NoEditor("No active editor"), outcome.await())
        assertNull(registry.get(sessionId))
        assertNull(registry.state.value.active)
    }
}
