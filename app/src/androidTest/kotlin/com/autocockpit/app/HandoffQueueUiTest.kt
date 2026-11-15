package com.autocockpit.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autocockpit.app.ui.UiTags
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.handoff.CompanionClient
import com.autocockpit.core.handoff.HandoffServerCore
import com.autocockpit.core.handoff.LoopbackTransport
import com.autocockpit.core.handoff.LossyTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end handoff through a lossy transport, also exercised from the UI test classpath. */
@RunWith(AndroidJUnit4::class)
class HandoffQueueUiTest {

    // The Compose test environment must exist before the Activity composes.
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(InstrumentedRules.launchIntent())

    @Test
    fun lossyHandoffPopulatesTheQueueNode() {
        val graph = AppGraph.get(InstrumentedRules.activity(activityRule))
        val server = HandoffServerCore(graph.handoffRepository)
        val transport = LossyTransport(
            delegate = LoopbackTransport(server),
            dropRate = 0.05,
            corruptRate = 0.02,
            disconnectAfterChunks = 3,
            seed = 21L,
        )
        val result = CompanionClient(transport).send(InstrumentedHarness.payload(9))
        assertTrue("handoff failed: ${result.failureReason}", result.success)
        assertTrue("expected a reconnect", result.reconnectAttempts >= 1)
        assertEquals(9, graph.handoffRepository.current()!!.queue.tracks.size)

        compose.onNodeWithTag(UiTags.NAV_MEDIA).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("handoff"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.mediaNode("handoff")).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("handoff:queue"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.mediaNode("handoff:queue")).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("phone-0"))).fetchSemanticsNodes().isNotEmpty()
        }

        // Degradation policy still reports a tier while the queue is populated.
        val monitor = com.autocockpit.core.health.PropertyHealthMonitor()
        assertEquals(UiTier.UNAVAILABLE, monitor.view(com.autocockpit.core.domain.VehicleSignal.Speed, 0L).tier)
    }
}
