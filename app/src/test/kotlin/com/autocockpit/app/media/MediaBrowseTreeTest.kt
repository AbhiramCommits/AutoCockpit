package com.autocockpit.app.media

import com.autocockpit.app.media.MediaBrowseTree.Companion.HANDOFF_NODE
import com.autocockpit.app.media.MediaBrowseTree.Companion.HANDOFF_QUEUE_NODE
import com.autocockpit.app.media.MediaBrowseTree.Companion.PLAYLISTS_NODE
import com.autocockpit.app.media.MediaBrowseTree.Companion.RECENT_LIST_NODE
import com.autocockpit.app.media.MediaBrowseTree.Companion.RECENT_NODE
import com.autocockpit.app.media.MediaBrowseTree.Companion.ROOT_ID
import com.autocockpit.app.media.MediaBrowseTree.Companion.playlistId
import com.autocockpit.core.TestPayloadsForApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaBrowseTreeTest {

    private val catalog = MediaCatalogRepository.generatedCatalog()
    private val tree = MediaBrowseTree(catalog)

    @Test
    fun rootHasThreeBrowsableNodes() {
        val root = tree.children(ROOT_ID, null, emptyList())
        assertEquals(3, root.size)
        assertEquals(listOf(PLAYLISTS_NODE, RECENT_NODE, HANDOFF_NODE), root.map { it.mediaId })
        assertTrue(root.all { it.isBrowsable && !it.isPlayable })
        assertEquals("Playlists", root[0].description.title.toString())
        assertEquals("4 playlists", root[0].description.subtitle.toString())
    }

    @Test
    fun playlistsNodeListsEveryPlaylist() {
        val items = tree.children(PLAYLISTS_NODE, null, emptyList())
        assertEquals(catalog.playlists.size, items.size)
        assertEquals(catalog.playlists.map { playlistId(it.id) }, items.map { it.mediaId })
        assertTrue(items.all { it.isBrowsable })
        assertEquals("Commute Loop", items[1].description.title.toString())
        assertEquals("14 tracks", items[1].description.subtitle.toString())
    }

    @Test
    fun playlistNodeContainsPlayableTracks() {
        val items = tree.children(playlistId("commute"), null, emptyList())
        assertEquals(14, items.size)
        assertTrue(items.all { it.isPlayable && !it.isBrowsable })
        assertEquals("t01", items.first().mediaId)
        assertEquals("Synthetic Track 01", items.first().description.title.toString())
        assertEquals("Synth Unit 7", items.first().description.subtitle.toString())
        assertNotNull(items.first().description.extras)
    }

    @Test
    fun unknownPlaylistIsEmpty() {
        assertTrue(tree.children(playlistId("nope"), null, emptyList()).isEmpty())
    }

    @Test
    fun recentNodeShowsEmptyState() {
        val recent = tree.children(RECENT_NODE, null, emptyList())
        assertEquals(1, recent.size)
        assertEquals(RECENT_LIST_NODE, recent[0].mediaId)
        assertEquals("Nothing played yet", recent[0].description.subtitle.toString())
        assertTrue(tree.children(RECENT_LIST_NODE, null, emptyList()).isEmpty())
    }

    @Test
    fun recentListRendersProvidedTracks() {
        val recent = catalog.tracks.take(3).map { it.toMediaTrack() }
        val items = tree.children(RECENT_LIST_NODE, null, recent)
        assertEquals(3, items.size)
        assertEquals("t01", items[0].mediaId)
        assertTrue(items.all { it.isPlayable })
    }

    @Test
    fun handoffNodeShowsWaitingStateWithoutPayload() {
        val items = tree.children(HANDOFF_NODE, null, emptyList())
        assertEquals(1, items.size)
        assertEquals(HANDOFF_QUEUE_NODE, items[0].mediaId)
        assertEquals("Waiting for companion handoff", items[0].description.subtitle.toString())
    }

    @Test
    fun handoffQueueExposesPayloadTracks() {
        val payload = TestPayloadsForApp.handoff(5)
        val node = tree.children(HANDOFF_NODE, payload, emptyList())
        assertEquals("5 tracks from Companion Driver", node[0].description.subtitle.toString())

        val tracks = tree.children(HANDOFF_QUEUE_NODE, payload, emptyList())
        assertEquals(5, tracks.size)
        assertTrue(tracks.all { it.isPlayable })
        assertEquals("companion-0", tracks[0].mediaId)
        assertEquals("Phone Track 0", tracks[0].description.title.toString())
    }

    @Test
    fun emptyHandoffQueueRendersNothing() {
        val payload = TestPayloadsForApp.handoff(0)
        assertTrue(tree.children(HANDOFF_QUEUE_NODE, payload, emptyList()).isEmpty())
    }

    @Test
    fun unknownNodeIsEmpty() {
        assertTrue(tree.children("bogus", null, emptyList()).isEmpty())
    }

    @Test
    fun treeCoversEveryCatalogTrack() {
        val all = tree.children(playlistId("all-tracks"), null, emptyList())
        assertEquals(40, all.size)
        assertTrue(all.all { it.isPlayable })
    }
}
