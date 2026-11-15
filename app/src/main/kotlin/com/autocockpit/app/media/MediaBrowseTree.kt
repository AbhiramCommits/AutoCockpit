package com.autocockpit.app.media

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaTrack

/**
 * Builds the hierarchical browse tree served by [AutoCockpitMediaService]:
 *
 * ```
 * root
 * ├── playlists            (browsable)
 * │   └── playlist:<id>    (tracks, playable)
 * ├── recent
 * │   └── recent:list      (tracks, playable)
 * └── handoff
 *     └── handoff:queue    (phone tracks, playable)
 * ```
 * Pure Android-view logic, no service state, so the tree is testable without a binder.
 */
class MediaBrowseTree(private val catalog: Catalog) {

    fun children(
        parentId: String,
        handoff: HandoffPayload?,
        recent: List<MediaTrack>,
    ): List<MediaBrowserCompat.MediaItem> {
        return when {
            parentId == ROOT_ID -> rootItems()
            parentId == PLAYLISTS_NODE -> catalog.playlists.map { playlist ->
                browsable(
                    id = playlistId(playlist.id),
                    title = playlist.name,
                    subtitle = "${playlist.trackIds.size} tracks",
                )
            }
            parentId == RECENT_NODE -> listOf(
                browsable(
                    id = RECENT_LIST_NODE,
                    title = "Recently Played",
                    subtitle = if (recent.isEmpty()) "Nothing played yet" else "${recent.size} tracks",
                ),
            )
            parentId == HANDOFF_NODE -> listOf(
                browsable(
                    id = HANDOFF_QUEUE_NODE,
                    title = "Phone Queue",
                    subtitle = handoff?.let { "${it.queue.tracks.size} tracks from ${it.profile.displayName}" }
                        ?: "Waiting for companion handoff",
                ),
            )
            parentId.startsWith(PLAYLIST_PREFIX) -> {
                val playlist = catalog.playlist(parentId.removePrefix(PLAYLIST_PREFIX))
                    ?: return emptyList()
                catalog.tracks(playlist.trackIds).map { playable(it.toMediaTrack()) }
            }
            parentId == RECENT_LIST_NODE -> recent.map(::playable)
            parentId == HANDOFF_QUEUE_NODE -> handoff?.queue?.tracks.orEmpty().map(::playable)
            else -> emptyList()
        }
    }

    fun rootItems(): List<MediaBrowserCompat.MediaItem> = listOf(
        browsable(PLAYLISTS_NODE, "Playlists", "${catalog.playlists.size} playlists"),
        browsable(RECENT_NODE, "Recently Played", "On this head unit"),
        browsable(HANDOFF_NODE, "Handoff Queue", "From the companion phone"),
    )

    private fun browsable(id: String, title: String, subtitle: String? = null): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun playable(track: MediaTrack): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(track.id)
            .setTitle(track.title)
            .setSubtitle(track.artist)
            .setExtras(android.os.Bundle().apply { putLong(EXTRA_DURATION_MS, track.durationMs) })
            .build()
        return MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    companion object {
        const val ROOT_ID = "root"
        const val PLAYLISTS_NODE = "playlists"
        const val RECENT_NODE = "recent"
        const val RECENT_LIST_NODE = "recent:list"
        const val HANDOFF_NODE = "handoff"
        const val HANDOFF_QUEUE_NODE = "handoff:queue"
        const val PLAYLIST_PREFIX = "playlist:"
        const val EXTRA_DURATION_MS = "com.autocockpit.extra.DURATION_MS"

        fun playlistId(id: String): String = "$PLAYLIST_PREFIX$id"
    }
}
