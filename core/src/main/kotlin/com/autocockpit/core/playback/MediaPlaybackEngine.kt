package com.autocockpit.core.playback

import com.autocockpit.core.domain.MediaTrack

/** Transport state mirrored into MediaSessionCompat PlaybackStateCompat. */
enum class PlaybackStatus {
    IDLE,
    PAUSED,
    PLAYING,
}

data class PlaybackSnapshot(
    val status: PlaybackStatus,
    val tracks: List<MediaTrack>,
    val index: Int,
    val positionMs: Long,
    val lastError: String? = null,
) {
    val current: MediaTrack? get() = tracks.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < tracks.size
    val hasPrevious: Boolean get() = index > 0
}

/**
 * Queue/transport state for the head-unit media browser. Deliberately free of Android types so
 * the transitions are unit-testable and the service only has to translate to MediaSession.
 *
 * All mutating calls are synchronized; the service ticks it from the main looper.
 */
class MediaPlaybackEngine(initialQueue: List<MediaTrack> = emptyList(), startIndex: Int = 0) {
    private val lock = Any()
    private var tracks: List<MediaTrack> = initialQueue
    private var index: Int = if (initialQueue.isEmpty()) 0 else startIndex.coerceIn(0, initialQueue.lastIndex)
    private var positionMs: Long = 0L
    private var status: PlaybackStatus = if (initialQueue.isEmpty()) PlaybackStatus.IDLE else PlaybackStatus.PAUSED
    private var lastError: String? = null

    fun snapshot(): PlaybackSnapshot = synchronized(lock) {
        PlaybackSnapshot(status, tracks, index, positionMs, lastError)
    }

    fun setQueue(newTracks: List<MediaTrack>, startIndex: Int = 0, play: Boolean = false): PlaybackSnapshot =
        synchronized(lock) {
            tracks = newTracks
            index = if (newTracks.isEmpty()) 0 else startIndex.coerceIn(0, newTracks.lastIndex)
            positionMs = 0L
            lastError = null
            status = when {
                newTracks.isEmpty() -> PlaybackStatus.IDLE
                play -> PlaybackStatus.PLAYING
                else -> PlaybackStatus.PAUSED
            }
            snapshotLocked()
        }

    fun play(): PlaybackSnapshot = synchronized(lock) {
        if (tracks.isNotEmpty()) {
            status = PlaybackStatus.PLAYING
            lastError = null
        } else {
            status = PlaybackStatus.IDLE
        }
        snapshotLocked()
    }

    fun pause(): PlaybackSnapshot = synchronized(lock) {
        if (status == PlaybackStatus.PLAYING) status = PlaybackStatus.PAUSED
        snapshotLocked()
    }

    fun toggle(): PlaybackSnapshot = synchronized(lock) {
        if (tracks.isEmpty()) {
            status = PlaybackStatus.IDLE
        } else {
            status = if (status == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING
        }
        snapshotLocked()
    }

    fun stop(): PlaybackSnapshot = synchronized(lock) {
        positionMs = 0L
        status = if (tracks.isEmpty()) PlaybackStatus.IDLE else PlaybackStatus.PAUSED
        snapshotLocked()
    }

    fun skipNext(): PlaybackSnapshot = synchronized(lock) {
        if (tracks.isEmpty()) return@synchronized snapshotLocked()
        if (index + 1 < tracks.size) {
            index += 1
            positionMs = 0L
        } else {
            // At the end of the queue: clamp at the last track and stop.
            positionMs = tracks[index].durationMs
            status = PlaybackStatus.PAUSED
        }
        snapshotLocked()
    }

    fun skipPrevious(): PlaybackSnapshot = synchronized(lock) {
        if (tracks.isEmpty()) return@synchronized snapshotLocked()
        if (positionMs > RESTART_THRESHOLD_MS || index == 0) {
            positionMs = 0L
        } else {
            index -= 1
            positionMs = 0L
        }
        snapshotLocked()
    }

    fun seekTo(positionMillis: Long): PlaybackSnapshot = synchronized(lock) {
        val track = tracks.getOrNull(index)
        positionMs = if (track == null) 0L else positionMillis.coerceIn(0L, track.durationMs)
        snapshotLocked()
    }

    fun skipToIndex(newIndex: Int, play: Boolean = true): PlaybackSnapshot = synchronized(lock) {
        if (newIndex !in tracks.indices) return@synchronized snapshotLocked()
        index = newIndex
        positionMs = 0L
        lastError = null
        if (play) status = PlaybackStatus.PLAYING
        snapshotLocked()
    }

    /** Advances playback by [deltaMillis] while playing, rolling into the next track. */
    fun tick(deltaMillis: Long): PlaybackSnapshot = synchronized(lock) {
        if (status != PlaybackStatus.PLAYING || deltaMillis <= 0) return@synchronized snapshotLocked()
        var remaining = deltaMillis
        var guard = 0
        while (remaining > 0 && guard++ < 64) {
            val track = tracks.getOrNull(index) ?: break
            val left = track.durationMs - positionMs
            if (remaining < left) {
                positionMs += remaining
                remaining = 0
            } else {
                remaining -= left
                if (index + 1 < tracks.size) {
                    index += 1
                    positionMs = 0L
                } else {
                    positionMs = track.durationMs
                    status = PlaybackStatus.PAUSED
                    remaining = 0
                }
            }
        }
        snapshotLocked()
    }

    fun fail(message: String): PlaybackSnapshot = synchronized(lock) {
        lastError = message
        status = PlaybackStatus.PAUSED
        snapshotLocked()
    }

    private fun snapshotLocked() = PlaybackSnapshot(status, tracks, index, positionMs, lastError)

    companion object {
        /** Seeking back this far (ms) restarts the current track instead of skipping. */
        const val RESTART_THRESHOLD_MS: Long = 3_000L
    }
}
