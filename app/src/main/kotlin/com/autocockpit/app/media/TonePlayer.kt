package com.autocockpit.app.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a synthesised sine tone for the current track, so playback really produces audio without
 * bundling copyrighted media. Every call is failure-tolerant: headless tests and images without
 * an audio HAL simply stay silent.
 */
class TonePlayer(private val sampleRate: Int = 44_100) {

    private var track: AudioTrack? = null
    private var lastUri: String? = null

    fun play(toneUri: String?) {
        if (toneUri == null) {
            stop()
            return
        }
        if (toneUri == lastUri && track != null) return
        stop()
        runCatching {
            val frequency = parseFrequency(toneUri)
            val frames = sampleRate // one second, looped by the hardware
            val pcm = renderSine(frequency, frames)
            val audio = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            audio.write(pcm, 0, pcm.size)
            audio.setLoopPoints(0, frames, -1)
            audio.setVolume(TONE_VOLUME)
            audio.play()
            track = audio
            lastUri = toneUri
        }.onFailure { Log.w(TAG, "tone playback unavailable: ${it.message}") }
    }

    fun stop() {
        runCatching {
            track?.stop()
        }
        runCatching {
            track?.release()
        }
        track = null
        lastUri = null
    }

    fun isPlaying(): Boolean = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    private fun renderSine(frequency: Double, frames: Int): ShortArray {
        val samples = ShortArray(frames)
        val gain = 0.18 * Short.MAX_VALUE
        for (i in 0 until frames) {
            samples[i] = (gain * sin(2.0 * PI * frequency * i / sampleRate)).toInt().toShort()
        }
        return samples
    }

    companion object {
        private const val TAG = "TonePlayer"
        private const val TONE_VOLUME = 0.08f

        /** Extracts the Hz value from `synth://tone/440?id=t01`. */
        fun parseFrequency(uri: String): Double {
            val marker = "tone/"
            val start = uri.indexOf(marker)
            if (start < 0) return 440.0
            val rest = uri.substring(start + marker.length)
            val digits = rest.takeWhile { it.isDigit() }.toDoubleOrNull() ?: 440.0
            return digits.coerceIn(60.0, 4_000.0)
        }
    }
}
