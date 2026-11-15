package com.autocockpit.core

import com.autocockpit.core.domain.DriverProfile
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.domain.SeatPosition

/** App-side handoff fixtures (package intentionally shares :core's root for concise imports). */
object TestPayloadsForApp {
    fun handoff(trackCount: Int): HandoffPayload {
        val tracks = (0 until trackCount).map { i ->
            MediaTrack(
                id = "companion-$i",
                title = "Phone Track $i",
                artist = "Phone Artist",
                durationMs = 90_000L + i * 1_000L,
                uri = "synth://tone/${300 + i * 10}?id=companion-$i",
            )
        }
        return HandoffPayload(
            profile = DriverProfile(
                driverId = "companion-driver",
                displayName = "Companion Driver",
                seat = SeatPosition.DRIVER,
                hvacSetpointC = 23.0f,
                preferredVolume = 30,
                themeAccent = "amber",
            ),
            queue = MediaQueue(tracks, position = 0, sourceLabel = "companion"),
            generatedAtMillis = 1_700_000_000_000L,
            schemaVersion = BuildInfo.SCHEMA_VERSION,
        )
    }
}
