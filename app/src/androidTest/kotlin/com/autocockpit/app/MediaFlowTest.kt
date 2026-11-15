package com.autocockpit.app

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autocockpit.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaFlowTest {

    // The Compose test environment must exist before the Activity composes.
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<MainActivity>(InstrumentedRules.launchIntent())

    private val harness = InstrumentedHarness.install()

    @Test
    fun browseTreeToPlaybackDrivesTheMediaSession() {
        compose.onNodeWithTag(UiTags.NAV_MEDIA).performClick()
        compose.onNodeWithTag(UiTags.MEDIA_SCREEN).assertIsDisplayed()

        // Root -> Playlists
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("playlists"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.mediaNode("playlists")).performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("playlist:commute"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.mediaNode("playlist:commute")).performClick()

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(UiTags.mediaNode("t01"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(UiTags.mediaNode("t01")).performClick()

        val controller = InstrumentedHarness.connectController(InstrumentedRules.activity(activityRule))
        assertEquals(
            PlaybackStateCompat.STATE_PLAYING,
            InstrumentedHarness.awaitPlaybackState(controller, PlaybackStateCompat.STATE_PLAYING),
        )
        assertEquals(
            "Synthetic Track 01",
            controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
        )
        compose.waitUntil(5_000) {
            compose.onAllNodes(
                hasTestTag(UiTags.MEDIA_NOW_PLAYING) and hasAnyDescendant(hasText("Synthetic Track 01")),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(
            hasTestTag(UiTags.MEDIA_NOW_PLAYING) and hasAnyDescendant(hasText("Synthetic Track 01")),
        ).assertIsDisplayed()

        // Pause via the transport control surfaced by the UI.
        compose.onNodeWithTag(UiTags.MEDIA_PLAY_TOGGLE).performClick()
        assertEquals(
            PlaybackStateCompat.STATE_PAUSED,
            InstrumentedHarness.awaitPlaybackState(controller, PlaybackStateCompat.STATE_PAUSED),
        )
    }

    @Test
    fun handoffQueueAppearsInTheBrowseTree() {
        val graph = harness
        val ok = InstrumentedHarness.runHandoff(graph.handoffRepository, trackCount = 6)
        check(ok) { "headless handoff failed" }

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
        compose.onNodeWithTag(UiTags.mediaNode("phone-0")).assertIsDisplayed()

        // Playing a handed-off track must move the session into the handoff queue.
        compose.onNodeWithTag(UiTags.mediaNode("phone-0")).performClick()
        val controller = InstrumentedHarness.connectController(InstrumentedRules.activity(activityRule))
        assertEquals(
            PlaybackStateCompat.STATE_PLAYING,
            InstrumentedHarness.awaitPlaybackState(controller, PlaybackStateCompat.STATE_PLAYING),
        )
        assertEquals("Phone Track 0", controller?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
    }
}
