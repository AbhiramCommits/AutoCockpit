package com.autocockpit.app.media

import com.autocockpit.core.domain.MediaTrack
import kotlinx.serialization.Serializable

@Serializable
data class CatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val toneHz: Int = 440,
) {
    fun toMediaTrack(): MediaTrack = MediaTrack(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs,
        uri = "synth://tone/$toneHz?id=$id",
    )
}

@Serializable
data class CatalogPlaylist(
    val id: String,
    val name: String,
    val trackIds: List<String>,
)

@Serializable
data class Catalog(
    val tracks: List<CatalogTrack>,
    val playlists: List<CatalogPlaylist>,
) {
    private val byId: Map<String, CatalogTrack> = tracks.associateBy { it.id }

    fun track(id: String): CatalogTrack? = byId[id]

    fun tracks(ids: List<String>): List<CatalogTrack> = ids.mapNotNull(byId::get)

    fun playlist(id: String): CatalogPlaylist? = playlists.firstOrNull { it.id == id }

    fun playlistsContaining(trackId: String): List<CatalogPlaylist> =
        playlists.filter { trackId in it.trackIds }
}
