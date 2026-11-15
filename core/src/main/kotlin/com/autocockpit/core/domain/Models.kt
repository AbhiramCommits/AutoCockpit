package com.autocockpit.core.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Seat the driver profile belongs to. Encoded as a single byte on the wire. */
@Serializable
enum class SeatPosition {
    DRIVER,
    PASSENGER,
    REAR_LEFT,
    REAR_RIGHT,
}

/**
 * Phone-side driver state handed to the head unit.
 *
 * @property hvacSetpointC preferred cabin temperature in Celsius
 * @property preferredVolume media volume, 0..100
 */
@Serializable
data class DriverProfile(
    val driverId: String,
    val displayName: String,
    val seat: SeatPosition = SeatPosition.DRIVER,
    val hvacSetpointC: Float = 21.5f,
    val preferredVolume: Int = 40,
    val themeAccent: String = "teal",
) {
    init {
        require(driverId.isNotBlank()) { "driverId must not be blank" }
        require(preferredVolume in 0..100) { "preferredVolume out of range: $preferredVolume" }
    }
}

/** A playable synthesised track. No bundled audio: playback is generated locally. */
@Serializable
data class MediaTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: String,
) {
    init {
        require(id.isNotBlank()) { "track id must not be blank" }
        require(durationMs > 0) { "durationMs must be positive: $durationMs" }
    }
}

/** Queue of tracks plus the playhead carried across the handoff. */
@Serializable
data class MediaQueue(
    val tracks: List<MediaTrack>,
    val position: Int = 0,
    val sourceLabel: String = "phone",
) {
    init {
        require(position >= 0) { "position must be >= 0: $position" }
        require(tracks.isEmpty() || position < tracks.size) { "position $position outside queue of ${tracks.size}" }
    }

    companion object {
        val EMPTY = MediaQueue(emptyList(), 0, "phone")
    }
}

/** Whole phone-side state transferred in one handoff frame. */
@Serializable
data class HandoffPayload(
    val profile: DriverProfile,
    val queue: MediaQueue,
    val generatedAtMillis: Long,
    val schemaVersion: Int,
)

/** Vehicle signals the head unit renders; mapped from android.car property ids in :app. */
@Serializable
sealed class VehicleSignal {
    @Serializable
    @SerialName("speed")
    data object Speed : VehicleSignal()

    @Serializable
    @SerialName("battery_level")
    data object BatteryLevel : VehicleSignal()

    @Serializable
    @SerialName("range_km")
    data object RangeKm : VehicleSignal()

    @Serializable
    @SerialName("charge_port_open")
    data object ChargePortOpen : VehicleSignal()

    @Serializable
    @SerialName("hvac_temp")
    data object HvacTemp : VehicleSignal()

    companion object {
        val all: List<VehicleSignal> = listOf(Speed, BatteryLevel, RangeKm, ChargePortOpen, HvacTemp)
    }
}

/** Health of a single sample as reported by the car property layer. */
@Serializable
enum class SampleStatus {
    AVAILABLE,
    UNAVAILABLE,
    STALE,
    ERROR,
}

/** One timestamped reading. `value` is raw (km/h, %, km, 0/1, Celsius). */
@Serializable
data class SignalSample(
    val signal: VehicleSignal,
    val value: Double,
    val timestampMillis: Long,
    val status: SampleStatus,
) {
    init {
        require(timestampMillis >= 0) { "timestampMillis must be non-negative" }
    }
}

/** UI tier the degradation policy assigns to a signal. */
@Serializable
enum class UiTier {
    LIVE,
    LAST_KNOWN,
    UNAVAILABLE,
}
