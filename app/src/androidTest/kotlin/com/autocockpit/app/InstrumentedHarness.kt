package com.autocockpit.app

import android.content.ComponentName
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.autocockpit.app.car.FakeCarPropertyGateway
import com.autocockpit.app.clock.Clock
import com.autocockpit.app.media.AutoCockpitMediaService
import com.autocockpit.core.domain.DriverProfile
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.domain.SeatPosition
import com.autocockpit.core.handoff.CompanionClient
import com.autocockpit.core.handoff.HandoffRepository
import com.autocockpit.core.handoff.HandoffServerCore
import com.autocockpit.core.handoff.LoopbackTransport

/** Controllable clock for the instrumented suite. */
class InstrumentedClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = now
    override fun elapsedRealtimeMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * Shared test wiring: replaces the car gateway and clock in the app graph, and provides a real
 * in-memory handoff pipeline that commits into the same repository the media browser reads.
 */
object InstrumentedHarness {

    val clock = InstrumentedClock()
    lateinit var gateway: FakeCarPropertyGateway
        private set

    fun install(): AppGraph {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = AppGraph.get(context)
        if (!::gateway.isInitialized) {
            gateway = FakeCarPropertyGateway(clock, initialValues = FakeCarPropertyGateway.DEFAULT_VALUES)
        }
        graph.clockOverride = clock
        graph.gatewayOverride = gateway
        graph.startCompanionLink()
        return graph
    }

    fun payload(trackCount: Int = 6): HandoffPayload {
        val tracks = (0 until trackCount).map { i ->
            MediaTrack(
                id = "phone-$i",
                title = "Phone Track $i",
                artist = "Companion Artist",
                durationMs = 95_000L + i * 1_000L,
                uri = "synth://tone/${320 + i * 20}?id=phone-$i",
            )
        }
        return HandoffPayload(
            profile = DriverProfile(
                driverId = "instrumented-driver",
                displayName = "Instrumented Driver",
                seat = SeatPosition.DRIVER,
                hvacSetpointC = 22.5f,
                preferredVolume = 35,
                themeAccent = "teal",
            ),
            queue = MediaQueue(tracks, position = 0, sourceLabel = "instrumented"),
            generatedAtMillis = clock.nowMillis(),
            schemaVersion = com.autocockpit.core.BuildInfo.SCHEMA_VERSION,
        )
    }

    /** Runs the real client/server handoff into the app's repository. */
    fun runHandoff(repository: HandoffRepository, trackCount: Int = 6): Boolean {
        val server = HandoffServerCore(repository)
        val transport = LoopbackTransport(server)
        val result = CompanionClient(transport).send(payload(trackCount))
        return result.success
    }

    fun connectController(activity: android.app.Activity, timeoutMillis: Long = 5_000L): MediaControllerCompat? {
        var browser: MediaBrowserCompat? = null
        var controller: MediaControllerCompat? = null
        activity.runOnUiThread {
            browser = MediaBrowserCompat(
                activity,
                ComponentName(activity, AutoCockpitMediaService::class.java),
                object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        controller = browser?.sessionToken?.let { MediaControllerCompat(activity, it) }
                    }
                },
                null,
            ).also { it.connect() }
        }
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (controller != null) return controller
            Thread.sleep(50)
        }
        return controller
    }

    fun awaitPlaybackState(
        controller: MediaControllerCompat?,
        expected: Int,
        timeoutMillis: Long = 5_000L,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var observed = PlaybackStateCompat.STATE_NONE
        while (System.currentTimeMillis() < deadline) {
            observed = controller?.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
            if (observed == expected) return observed
            Thread.sleep(50)
        }
        return observed
    }
}
