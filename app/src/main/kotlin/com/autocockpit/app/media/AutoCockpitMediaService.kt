package com.autocockpit.app.media

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.VisibleForTesting
import androidx.media.MediaBrowserServiceCompat
import com.autocockpit.app.AppGraph
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.playback.MediaPlaybackEngine
import com.autocockpit.core.playback.PlaybackSnapshot
import com.autocockpit.core.playback.PlaybackStatus

/**
 * AAOS media browser. Serves the hierarchical catalog, the recently played list and the phone
 * handoff queue, and translates [MediaPlaybackEngine] state into MediaSessionCompat state.
 * Playback really advances (a ticking position) and produces a synthesised tone.
 */
class AutoCockpitMediaService : MediaBrowserServiceCompat() {

    private lateinit var graph: AppGraph
    private lateinit var catalog: Catalog
    private lateinit var browseTree: MediaBrowseTree
    private lateinit var session: MediaSessionCompat
    private lateinit var tonePlayer: TonePlayer
    private val engine = MediaPlaybackEngine()
    private val handler = Handler(Looper.getMainLooper())
    private val recentTrackIds = ArrayDeque<String>()
    private var handoff: HandoffPayload? = null
    private var handoffSubscription: AutoCloseable? = null
    private var lastTickAt: Long = 0L
    private var publishedPlaybackState: PlaybackStateCompat? = null
    private var publishedMetadata: MediaMetadataCompat? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        catalog = graph.catalog
        browseTree = MediaBrowseTree(catalog)
        tonePlayer = TonePlayer()
        handoff = graph.handoffRepository.current()

        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(SessionCallback(), handler)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(TRANSPORT_ACTIONS)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build(),
            )
            isActive = true
        }
        sessionToken = session.sessionToken

        handoffSubscription = graph.handoffRepository.observe { payload ->
            handoff = payload
            handler.post {
                notifyChildrenChanged(MediaBrowseTree.HANDOFF_NODE)
                notifyChildrenChanged(MediaBrowseTree.HANDOFF_QUEUE_NODE)
            }
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot(
        MediaBrowseTree.ROOT_ID,
        Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) },
    )

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.sendResult(browseChildren(parentId))
    }

    @VisibleForTesting
    internal fun browseChildren(parentId: String): List<MediaBrowserCompat.MediaItem> =
        browseTree.children(parentId, handoff, recentTracks())

    @VisibleForTesting
    internal fun sessionForTest(): MediaSessionCompat = session

    @VisibleForTesting
    internal fun engineForTest(): MediaPlaybackEngine = engine

    private fun recentTracks(): List<MediaTrack> =
        recentTrackIds.mapNotNull(::trackById)

    private fun trackById(id: String): MediaTrack? =
        catalog.track(id)?.toMediaTrack() ?: handoff?.queue?.tracks?.firstOrNull { it.id == id }

    /** Resolves the queue a track belongs to: playlist first, then handoff, then the full catalog. */
    private fun queueForTrack(id: String): Pair<List<MediaTrack>, Int>? {
        catalog.playlistsContaining(id).firstOrNull()?.let { playlist ->
            val tracks = catalog.tracks(playlist.trackIds).map { it.toMediaTrack() }
            val index = tracks.indexOfFirst { it.id == id }
            if (index >= 0) return tracks to index
        }
        handoff?.queue?.tracks?.let { tracks ->
            val index = tracks.indexOfFirst { it.id == id }
            if (index >= 0) return tracks to index
        }
        val all = catalog.tracks.map { it.toMediaTrack() }
        val index = all.indexOfFirst { it.id == id }
        return if (index >= 0) all to index else null
    }

    private fun playTrackById(mediaId: String) {
        val resolved = queueForTrack(mediaId) ?: return
        val (tracks, index) = resolved
        val snapshot = engine.setQueue(tracks, index, play = true)
        rememberRecent(mediaId)
        afterTransportChange(snapshot, startAudio = true)
    }

    private fun rememberRecent(mediaId: String) {
        recentTrackIds.remove(mediaId)
        recentTrackIds.addFirst(mediaId)
        while (recentTrackIds.size > RECENT_LIMIT) recentTrackIds.removeLast()
        handler.post { notifyChildrenChanged(MediaBrowseTree.RECENT_LIST_NODE) }
    }

    private fun afterTransportChange(snapshot: PlaybackSnapshot, startAudio: Boolean) {
        publish(snapshot)
        if (snapshot.status == PlaybackStatus.PLAYING) {
            if (startAudio) tonePlayer.play(toneUri(snapshot.current))
            startTicker()
        } else {
            tonePlayer.stop()
            stopTicker()
        }
    }

    private fun toneUri(track: MediaTrack?): String? = track?.uri

    private fun publish(snapshot: PlaybackSnapshot = engine.snapshot()) {
        val state = when (snapshot.status) {
            PlaybackStatus.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackStatus.IDLE -> PlaybackStateCompat.STATE_NONE
        }
        val speed = if (snapshot.status == PlaybackStatus.PLAYING) 1f else 0f
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(TRANSPORT_ACTIONS)
            .setState(state, snapshot.positionMs, speed)
            .build()
        val metadata = snapshot.current?.let(::metadataFor)
        publishedPlaybackState = playbackState
        publishedMetadata = metadata
        session.setPlaybackState(playbackState)
        session.setMetadata(metadata)
    }

    private fun metadataFor(track: MediaTrack): MediaMetadataCompat =
        MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            .build()

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        lastTickAt = SystemClock.elapsedRealtime()
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val delta = (now - lastTickAt).coerceIn(0L, 2_000L)
            lastTickAt = now
            val before = engine.snapshot().current?.id
            val snapshot = engine.tick(delta)
            publish(snapshot)
            if (snapshot.current?.id != before) {
                tonePlayer.play(toneUri(snapshot.current))
            }
            if (snapshot.status == PlaybackStatus.PLAYING) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            } else {
                tonePlayer.stop()
            }
        }
    }

    // Transport dispatch entry points. The MediaSession callback delegates here so Robolectric
    // can exercise the exact same state publication without a framework binder round trip.

    @VisibleForTesting
    internal fun dispatchPlay() = afterTransportChange(engine.play(), startAudio = true)

    @VisibleForTesting
    internal fun dispatchPause() = afterTransportChange(engine.pause(), startAudio = false)

    @VisibleForTesting
    internal fun dispatchStop() = afterTransportChange(engine.stop(), startAudio = false)

    @VisibleForTesting
    internal fun dispatchSkipNext() = afterTransportChange(engine.skipNext(), startAudio = true)

    @VisibleForTesting
    internal fun dispatchSkipPrevious() = afterTransportChange(engine.skipPrevious(), startAudio = true)

    @VisibleForTesting
    internal fun dispatchSeekTo(positionMs: Long) = publish(engine.seekTo(positionMs))

    @VisibleForTesting
    internal fun dispatchPlayFromMediaId(mediaId: String) = playTrackById(mediaId)

    @VisibleForTesting
    internal fun publishedState(): PlaybackStateCompat? = publishedPlaybackState

    @VisibleForTesting
    internal fun publishedTrackMetadata(): MediaMetadataCompat? = publishedMetadata

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            dispatchPlay()
        }

        override fun onPause() {
            dispatchPause()
        }

        override fun onStop() {
            dispatchStop()
        }

        override fun onSkipToNext() {
            dispatchSkipNext()
        }

        override fun onSkipToPrevious() {
            dispatchSkipPrevious()
        }

        override fun onSeekTo(pos: Long) {
            dispatchSeekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.let(::dispatchPlayFromMediaId)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val match = catalog.tracks.firstOrNull {
                it.title.contains(query.orEmpty(), ignoreCase = true)
            } ?: catalog.tracks.firstOrNull()
            match?.let { playTrackById(it.id) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopTicker()
        tonePlayer.stop()
        engine.pause()
        publish()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopTicker()
        tonePlayer.stop()
        handoffSubscription?.close()
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val SESSION_TAG = "AutoCockpitMedia"
        private const val TICK_INTERVAL_MS = 250L
        private const val RECENT_LIMIT = 10

        private const val TRANSPORT_ACTIONS: Long =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
    }
}
