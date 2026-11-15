package com.autocockpit.core.playback

import com.autocockpit.core.domain.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackEngineTest {

    private fun tracks(count: Int) = (0 until count).map { i ->
        MediaTrack("id$i", "Track $i", "Artist", 10_000L, "synth://tone/440?id=id$i")
    }

    @Test
    fun emptyEngineIsIdle() {
        val engine = MediaPlaybackEngine()
        assertEquals(PlaybackStatus.IDLE, engine.snapshot().status)
        assertEquals(0, engine.snapshot().tracks.size)
        assertEquals(PlaybackStatus.IDLE, engine.play().status)
    }

    @Test
    fun setQueueWithPlayStartsAtRequestedIndex() {
        val engine = MediaPlaybackEngine()
        val snapshot = engine.setQueue(tracks(3), startIndex = 1, play = true)
        assertEquals(PlaybackStatus.PLAYING, snapshot.status)
        assertEquals(1, snapshot.index)
        assertEquals("id1", snapshot.current?.id)
        assertTrue(snapshot.hasNext)
        assertTrue(snapshot.hasPrevious)
    }

    @Test
    fun pauseAndToggleSwitchState() {
        val engine = MediaPlaybackEngine(tracks(2))
        engine.play()
        assertEquals(PlaybackStatus.PAUSED, engine.pause().status)
        assertEquals(PlaybackStatus.PLAYING, engine.toggle().status)
        assertEquals(PlaybackStatus.PAUSED, engine.toggle().status)
    }

    @Test
    fun tickAdvancesPositionWhilePlaying() {
        val engine = MediaPlaybackEngine(tracks(2)).apply { play() }
        val snapshot = engine.tick(2_500L)
        assertEquals(2_500L, snapshot.positionMs)
        assertEquals(0, snapshot.index)
    }

    @Test
    fun tickDoesNotAdvanceWhilePaused() {
        val engine = MediaPlaybackEngine(tracks(2))
        assertEquals(0L, engine.tick(5_000L).positionMs)
    }

    @Test
    fun tickRollsIntoNextTrack() {
        val engine = MediaPlaybackEngine(tracks(3)).apply { play() }
        val snapshot = engine.tick(12_000L)
        assertEquals(1, snapshot.index)
        assertEquals(2_000L, snapshot.positionMs)
    }

    @Test
    fun tickAtEndOfQueuePauses() {
        val engine = MediaPlaybackEngine(tracks(1)).apply { play() }
        val snapshot = engine.tick(20_000L)
        assertEquals(PlaybackStatus.PAUSED, snapshot.status)
        assertEquals(10_000L, snapshot.positionMs)
        assertEquals(0, snapshot.index)
    }

    @Test
    fun skipNextMovesForwardAndClampsAtTheEnd() {
        val engine = MediaPlaybackEngine(tracks(2)).apply { play() }
        assertEquals(1, engine.skipNext().index)
        val clamped = engine.skipNext()
        assertEquals(1, clamped.index)
        assertEquals(PlaybackStatus.PAUSED, clamped.status)
    }

    @Test
    fun skipPreviousRestartsTrackWhenPastThreshold() {
        val engine = MediaPlaybackEngine(tracks(3), startIndex = 2).apply { play() }
        engine.seekTo(5_000L)
        val restarted = engine.skipPrevious()
        assertEquals(2, restarted.index)
        assertEquals(0L, restarted.positionMs)
        val moved = engine.skipPrevious()
        assertEquals(1, moved.index)
    }

    @Test
    fun seekIsClampedToTrackDuration() {
        val engine = MediaPlaybackEngine(tracks(2))
        assertEquals(0L, engine.seekTo(-5L).positionMs)
        assertEquals(10_000L, engine.seekTo(99_999L).positionMs)
        assertEquals(3_000L, engine.seekTo(3_000L).positionMs)
    }

    @Test
    fun skipToIndexIgnoresOutOfRange() {
        val engine = MediaPlaybackEngine(tracks(2))
        assertEquals(0, engine.skipToIndex(9).index)
        assertEquals(1, engine.skipToIndex(1).index)
    }

    @Test
    fun failPausesWithError() {
        val engine = MediaPlaybackEngine(tracks(1)).apply { play() }
        val snapshot = engine.fail("audio device lost")
        assertEquals(PlaybackStatus.PAUSED, snapshot.status)
        assertEquals("audio device lost", snapshot.lastError)
    }

    @Test
    fun stopRewindsToStart() {
        val engine = MediaPlaybackEngine(tracks(2), startIndex = 1).apply { play() }
        engine.seekTo(4_000L)
        val snapshot = engine.stop()
        assertEquals(0L, snapshot.positionMs)
        assertFalse(snapshot.status == PlaybackStatus.PLAYING)
    }
}
