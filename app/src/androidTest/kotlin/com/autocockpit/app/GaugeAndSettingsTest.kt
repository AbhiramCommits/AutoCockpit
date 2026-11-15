package com.autocockpit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autocockpit.app.ui.UiTags
import com.autocockpit.gauge.GaugeView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GaugeAndSettingsTest {

    // The Compose test environment must exist before the Activity composes.
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(InstrumentedRules.launchIntent())

    private val harness = InstrumentedHarness.install()

    private fun awaitCondition(timeoutMillis: Long, description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("timed out waiting for $description")
    }

    private fun rowHasTier(signal: String, tierText: String): Boolean =
        compose.onAllNodes(
            hasTestTag(UiTags.signalRow(signal)) and hasAnyDescendant(hasText(tierText, substring = true)),
        ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun gaugeRendersAndReportsFrames() {
        compose.onNodeWithTag(UiTags.NAV_GAUGE).performClick()
        compose.onNodeWithTag(UiTags.GAUGE_SCREEN).assertIsDisplayed()

        awaitCondition(10_000, "gauge GL surface") { GaugeView.latest() != null }
        val view = GaugeView.latest()
        assertNotNull(view)
        // GL surface creation is asynchronous: wait for the program or a shader error.
        awaitCondition(15_000, "GL program") {
            val renderer = view!!.rendererForTest
            renderer.programHandle != 0 || renderer.lastError != null
        }
        assertTrue(view!!.rendererForTest.programHandle != 0 || view.rendererForTest.lastError != null)

        awaitCondition(15_000, "rendered frames") { view.frameStats().count > 0 }
        assertTrue("mean frame time should be positive", view.frameStats().meanMs > 0.0)

        // Collect a meaningful window, then export it for results/frame_metrics.json.
        awaitCondition(25_000, "300 rendered frames") { view.frameStats().count >= 300 }
        val stats = view.frameStats()
        assertTrue("no frames recorded", stats.count >= 300)
        val metricsFile = java.io.File(
            InstrumentedRules.activity(activityRule).filesDir,
            "gauge_frame_metrics.json",
        )
        view.exportFrameMetrics(metricsFile)
        android.util.Log.i(
            "AutoCockpitBenchmark",
            "AUTOCOCKPIT_FRAME_JSON:" + stats.toJson().replace("\n", " "),
        )

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(UiTags.GAUGE_TIER_LABEL)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.GAUGE_TIER_LABEL).assertIsDisplayed()
    }

    @Test
    fun settingsDegradesLiveToLastKnownToUnavailableAndRecovers() {
        compose.onNodeWithTag(UiTags.NAV_SETTINGS).performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_SCREEN).assertIsDisplayed()

        awaitCondition(5_000, "LIVE speed") { rowHasTier("Speed", "LIVE") }

        // Past the 3 s stale window with no new samples.
        InstrumentedHarness.clock.advance(4_000)
        awaitCondition(5_000, "LAST KNOWN speed") { rowHasTier("Speed", "LAST KNOWN") }

        // Past the 12 s fallback window.
        InstrumentedHarness.clock.advance(20_000)
        awaitCondition(5_000, "UNAVAILABLE speed") { rowHasTier("Speed", "UNAVAILABLE") }

        // Property data returns.
        InstrumentedHarness.gateway.emitAll(timestampMillis = InstrumentedHarness.clock.nowMillis())
        awaitCondition(5_000, "recovered LIVE speed") { rowHasTier("Speed", "LIVE") }
    }

    @Test
    fun handoffResyncAndLinkDropKeepTheUiResponsive() {
        val graph = harness
        check(InstrumentedHarness.runHandoff(graph.handoffRepository, trackCount = 3))

        compose.onNodeWithTag(UiTags.NAV_SETTINGS).performClick()
        awaitCondition(5_000, "re-sync button") {
            compose.onAllNodes(hasTestTag(UiTags.SETTINGS_RESYNC)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.SETTINGS_RESYNC).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(UiTags.SETTINGS_LINK_STATE) and hasText("SYNCED")).fetchSemanticsNodes().isNotEmpty()
        }
        check(graph.linkController.state.value.state.name == "SYNCED") {
            "re-sync did not reach SYNCED: ${graph.linkController.state.value.lastError}"
        }
        compose.onNodeWithTag(UiTags.SETTINGS_LINK_STATE).performScrollTo().assertIsDisplayed()

        // Simulate the GATT disconnect callback.
        graph.linkController.reportLinkLost()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(UiTags.SETTINGS_LINK_STATE) and hasText("DEGRADED")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(UiTags.SETTINGS_LINK_ATTEMPT) and hasText("attempt 1", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The rest of the cockpit still works after the drop: no ANR, no stuck screen.
        compose.onNodeWithTag(UiTags.NAV_MEDIA).performClick()
        compose.onNodeWithTag(UiTags.MEDIA_SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NAV_SETTINGS).performClick()
        compose.onNodeWithTag(UiTags.SETTINGS_SCREEN).assertIsDisplayed()
        compose.onNodeWithText("Companion phone").performScrollTo().assertIsDisplayed()
    }
}
