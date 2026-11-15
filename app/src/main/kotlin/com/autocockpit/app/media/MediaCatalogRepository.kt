package com.autocockpit.app.media

import android.content.res.AssetManager
import kotlinx.serialization.json.Json

/**
 * Loads the bundled synthetic catalog (~40 tracks, no copyrighted audio). Falls back to a
 * generated catalog when assets are unavailable (for example a stripped test configuration).
 */
class MediaCatalogRepository(private val assets: AssetManager?) {

    @Volatile
    private var cached: Catalog? = null

    fun load(): Catalog = cached ?: synchronized(this) {
        cached ?: readCatalog().also { cached = it }
    }

    private fun readCatalog(): Catalog {
        val text = runCatching {
            assets?.open(CATALOG_ASSET)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) return generatedCatalog()
        return runCatching { JSON.decodeFromString<Catalog>(text) }.getOrElse { generatedCatalog() }
    }

    companion object {
        const val CATALOG_ASSET = "catalog.json"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Same shape as catalog.json, generated in code so the app never starts without music. */
        fun generatedCatalog(): Catalog {
            val artists = listOf("Synth Unit 7", "Oscillator Lab", "Chassis Tone", "Voltage Bloom", "Night Bus")
            val tracks = (1..40).map { n ->
                CatalogTrack(
                    id = "t%02d".format(n),
                    title = "Synthetic Track %02d".format(n),
                    artist = artists[(n - 1) % artists.size],
                    durationMs = 150_000L + (n % 9) * 12_000L,
                    toneHz = 196 + (n % 12) * 32,
                )
            }
            val playlists = listOf(
                CatalogPlaylist("night-drive", "Night Drive", tracks.filterIndexed { i, _ -> i % 2 == 0 }.map { it.id }),
                CatalogPlaylist("commute", "Commute Loop", tracks.take(14).map { it.id }),
                CatalogPlaylist("ev-calm", "EV Calm", tracks.drop(20).take(12).map { it.id }),
                CatalogPlaylist("all-tracks", "All Tracks", tracks.map { it.id }),
            )
            return Catalog(tracks, playlists)
        }
    }
}
