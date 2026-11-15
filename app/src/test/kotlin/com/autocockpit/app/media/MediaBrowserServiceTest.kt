package com.autocockpit.app.media

import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.autocockpit.app.AppGraph
import com.autocockpit.core.TestPayloadsForApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaBrowserServiceTest {

    private lateinit var controller: ServiceController<AutoCockpitMediaService>
    private lateinit var service: AutoCockpitMediaService

    @Before
    fun setUp() {
        AppGraph.resetForTests()
        controller = Robolectric.buildService(AutoCockpitMediaService::class.java).create()
        service = controller.get()
        idle()
    }

    @After
    fun tearDown() {
        controller.destroy()
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onGetRootReturnsLibraryRoot() {
        val root = service.onGetRoot("com.autocockpit.app", 0, null)
        assertNotNull(root)
        assertEquals(MediaBrowseTree.ROOT_ID, root!!.rootId)
        assertTrue(root.extras?.getBoolean(androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT) == true)
    }

    @Test
    fun browseRootExposesThreeDestinations() {
        val items = service.browseChildren(MediaBrowseTree.ROOT_ID)
        assertEquals(
            listOf(MediaBrowseTree.PLAYLISTS_NODE, MediaBrowseTree.RECENT_NODE, MediaBrowseTree.HANDOFF_NODE),
            items.map { it.mediaId },
        )
        assertTrue(items.all { it.isBrowsable })
    }

    @Test
    fun playlistChildrenArePlayableTracks() {
        val items = service.browseChildren(MediaBrowseTree.playlistId("night-drive"))
        assertEquals(20, items.size)
        assertTrue(items.all { it.isPlayable })
        assertEquals("Synthetic Track 01", items.first().description.title.toString())
    }

    @Test
    fun handoffQueueIsPopulatedFromRepository() {
        assertTrue(service.browseChildren(MediaBrowseTree.HANDOFF_QUEUE_NODE).isEmpty())

        AppGraph.get(service).handoffRepository.commit(TestPayloadsForApp.handoff(7))
        idle()

        val items = service.browseChildren(MediaBrowseTree.HANDOFF_QUEUE_NODE)
        assertEquals(7, items.size)
        assertEquals("companion-0", items.first().mediaId)
        val node = service.browseChildren(MediaBrowseTree.HANDOFF_NODE).single()
        assertEquals("7 tracks from Companion Driver", node.description.subtitle.toString())
    }

    private fun metadataString(key: String): String? =
        service.publishedTrackMetadata()?.getString(key)

    @Test
    fun playFromMediaIdStartsPlaybackAndPublishesMetadata() {
        service.dispatchPlayFromMediaId("t01")
        idle()

        val state = service.publishedState()!!
        assertEquals(PlaybackStateCompat.STATE_PLAYING, state.state)
        assertEquals(1f, state.playbackSpeed, 0.001f)
        assertEquals("Synthetic Track 01", metadataString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertEquals("Synth Unit 7", metadataString(MediaMetadataCompat.METADATA_KEY_ARTIST))
        assertTrue(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
        assertTrue(state.actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L)
        assertTrue(state.actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L)
        // The session token still carries the same published state for real clients.
        assertEquals(PlaybackStateCompat.STATE_PLAYING, service.sessionForTest().controller.playbackState.state)
    }

    @Test
    fun pauseAndResumeFollowTransportControls() {
        service.dispatchPlayFromMediaId("t01")
        assertEquals(PlaybackStateCompat.STATE_PLAYING, service.publishedState()!!.state)

        service.dispatchPause()
        assertEquals(PlaybackStateCompat.STATE_PAUSED, service.publishedState()!!.state)

        service.dispatchPlay()
        assertEquals(PlaybackStateCompat.STATE_PLAYING, service.publishedState()!!.state)
    }

    @Test
    fun skipNextAdvancesQueueAndMetadata() {
        service.dispatchPlayFromMediaId("t01")
        // t01 lives in the Night Drive playlist (every other track), so next is t03.
        service.dispatchSkipNext()
        assertEquals("Synthetic Track 03", metadataString(MediaMetadataCompat.METADATA_KEY_TITLE))
        service.dispatchSkipPrevious()
        assertEquals("Synthetic Track 01", metadataString(MediaMetadataCompat.METADATA_KEY_TITLE))
    }

    @Test
    fun seekMovesPlaybackPosition() {
        service.dispatchPlayFromMediaId("t01")
        service.dispatchSeekTo(5_000L)
        assertEquals(5_000L, service.publishedState()!!.position)
    }

    @Test
    fun playbackPositionAdvancesWithTheTicker() {
        service.dispatchPlayFromMediaId("t01")
        val before = service.publishedState()!!.position
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        val after = service.publishedState()!!.position
        assertTrue("position did not advance: $before -> $after", after > before)
    }

    @Test
    fun playingATrackLoadsItsWholePlaylistQueue() {
        service.dispatchPlayFromMediaId("t02")
        val queue = service.engineForTest().snapshot()
        assertEquals("Synthetic Track 02", metadataString(MediaMetadataCompat.METADATA_KEY_TITLE))
        assertTrue("queue should hold the playlist", queue.tracks.size >= 14)
        assertEquals(1, queue.index)
    }
}
