package com.autocockpit.core.health

import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal

/**
 * Tier thresholds for the cockpit UI.
 *
 * @property staleAfterMillis a fresh reading becomes LAST_KNOWN after this age
 * @property unavailableAfterMillis after this age the stale value is dropped entirely
 * @property errorBurstThreshold consecutive ERROR samples within [errorBurstWindowMillis] force UNAVAILABLE
 */
data class DegradationPolicy(
    val staleAfterMillis: Long = 3_000L,
    val unavailableAfterMillis: Long = 12_000L,
    val errorBurstThreshold: Int = 3,
    val errorBurstWindowMillis: Long = 5_000L,
) {
    init {
        require(staleAfterMillis > 0) { "staleAfterMillis must be positive" }
        require(unavailableAfterMillis > staleAfterMillis) { "unavailableAfterMillis must exceed staleAfterMillis" }
        require(errorBurstThreshold >= 1) { "errorBurstThreshold must be >= 1" }
    }
}

/** What the UI should render for one signal right now. */
data class SignalView(
    val signal: VehicleSignal,
    val tier: UiTier,
    /** Last known good value, or null when nothing usable has ever arrived. */
    val value: Double?,
    /** Age of [value] in milliseconds, or -1 when there is no value. */
    val ageMillis: Long,
    val lastStatus: SampleStatus?,
    val reason: String? = null,
) {
    val isDegraded: Boolean get() = tier != UiTier.LIVE
}

/**
 * Counters used by the simulation report to show LIVE vs LAST_KNOWN vs UNAVAILABLE frames.
 * A frame here is one signal-frame: [observeFrame] counts one per signal, so the three tiers
 * always add up to [framesObserved].
 */
data class DegradationCounters(
    val framesObserved: Int = 0,
    val liveFrames: Int = 0,
    val lastKnownFrames: Int = 0,
    val unavailableFrames: Int = 0,
) {
    val livePct: Double get() = pct(liveFrames)
    val lastKnownPct: Double get() = pct(lastKnownFrames)
    val unavailablePct: Double get() = pct(unavailableFrames)

    private fun pct(count: Int): Double =
        if (framesObserved == 0) 0.0 else (count.toDouble() * 100.0 / framesObserved)
}

private data class SignalRecord(
    val lastGoodValue: Double? = null,
    val lastGoodAtMillis: Long = -1L,
    val lastSample: SignalSample? = null,
    val errorTimestamps: List<Long> = emptyList(),
)

/**
 * Turns a stream of [SignalSample] into a per-signal [SignalView] with LIVE / LAST_KNOWN /
 * UNAVAILABLE tiers plus the fallback value and its age. This is the component that lets the
 * gauge desaturate and label a stale reading instead of crashing or freezing.
 *
 * The monitor is deterministic and clock-injected: callers pass `nowMillis` to [evaluate] and
 * [observeFrame], so tests and the simulation can advance time without sleeping.
 */
class PropertyHealthMonitor(
    val policy: DegradationPolicy = DegradationPolicy(),
) {
    private val records = HashMap<VehicleSignal, SignalRecord>()
    private var counters = DegradationCounters()

    /** Feed one sample. Out-of-order samples older than the last good value are ignored. */
    @Synchronized
    fun onSample(sample: SignalSample) {
        val record = records[sample.signal] ?: SignalRecord()
        val updated = when (sample.status) {
            SampleStatus.AVAILABLE -> {
                val isNewer = sample.timestampMillis >= record.lastGoodAtMillis
                val good = if (isNewer) {
                    record.copy(
                        lastGoodValue = sample.value,
                        lastGoodAtMillis = sample.timestampMillis,
                        lastSample = sample,
                        errorTimestamps = emptyList(),
                    )
                } else {
                    record.copy(lastSample = sample)
                }
                good
            }
            SampleStatus.ERROR -> record.copy(
                lastSample = sample,
                errorTimestamps = (record.errorTimestamps + sample.timestampMillis)
                    .takeLast(policy.errorBurstThreshold * 2),
            )
            SampleStatus.STALE, SampleStatus.UNAVAILABLE -> record.copy(lastSample = sample)
        }
        records[sample.signal] = updated
    }

    @Synchronized
    fun onSamples(samples: Iterable<SignalSample>) = samples.forEach(::onSample)

    /** Evaluate one signal without touching frame counters. */
    @Synchronized
    fun view(signal: VehicleSignal, nowMillis: Long): SignalView {
        val record = records[signal]
            ?: return SignalView(signal, UiTier.UNAVAILABLE, null, -1L, null, "no sample")
        val last = record.lastSample
        val goodValue = record.lastGoodValue
        val goodAge = if (record.lastGoodAtMillis >= 0) (nowMillis - record.lastGoodAtMillis).coerceAtLeast(0) else -1L

        fun tierFor(age: Long): UiTier = when {
            age < 0 -> UiTier.UNAVAILABLE
            age <= policy.staleAfterMillis -> UiTier.LIVE
            age <= policy.unavailableAfterMillis -> UiTier.LAST_KNOWN
            else -> UiTier.UNAVAILABLE
        }

        if (last == null) {
            return SignalView(signal, UiTier.UNAVAILABLE, goodValue, goodAge, null, "no sample")
        }
        val burst = last.status == SampleStatus.ERROR &&
            record.errorTimestamps.count { nowMillis - it <= policy.errorBurstWindowMillis } >= policy.errorBurstThreshold

        return when {
            last.status == SampleStatus.UNAVAILABLE ->
                SignalView(signal, UiTier.UNAVAILABLE, goodValue, goodAge, last.status, "reported unavailable")

            burst ->
                SignalView(
                    signal, UiTier.UNAVAILABLE, goodValue, goodAge, last.status,
                    "error burst (${policy.errorBurstThreshold} within ${policy.errorBurstWindowMillis}ms)",
                )

            last.status == SampleStatus.ERROR ->
                SignalView(signal, UiTier.LAST_KNOWN, goodValue, goodAge, last.status, "property error")

            last.status == SampleStatus.STALE -> {
                val tier = if (goodAge in 0..policy.unavailableAfterMillis) UiTier.LAST_KNOWN else UiTier.UNAVAILABLE
                SignalView(signal, tier, goodValue, goodAge, last.status, "stale sample")
            }

            else -> {
                // Fresh AVAILABLE: tier is driven by the age of the reading itself.
                val age = (nowMillis - last.timestampMillis).coerceAtLeast(0)
                val tier = tierFor(age)
                val reason = when (tier) {
                    UiTier.LIVE -> null
                    UiTier.LAST_KNOWN -> "no update for ${age}ms"
                    UiTier.UNAVAILABLE -> "no update for ${age}ms, fallback expired"
                }
                SignalView(signal, tier, goodValue, age, last.status, reason)
            }
        }
    }

    /** Evaluate every known signal without touching the frame counters (UI refresh). */
    @Synchronized
    fun evaluateAll(nowMillis: Long): Map<VehicleSignal, SignalView> =
        VehicleSignal.all.associateWith { view(it, nowMillis) }

    /** Evaluate every known signal and count this frame for the degradation statistics. */
    @Synchronized
    fun observeFrame(nowMillis: Long): Map<VehicleSignal, SignalView> {
        val views = VehicleSignal.all.associateWith { view(it, nowMillis) }
        var live = 0
        var lastKnown = 0
        var unavailable = 0
        for (view in views.values) {
            when (view.tier) {
                UiTier.LIVE -> live++
                UiTier.LAST_KNOWN -> lastKnown++
                UiTier.UNAVAILABLE -> unavailable++
            }
        }
        counters = DegradationCounters(
            framesObserved = counters.framesObserved + views.size,
            liveFrames = counters.liveFrames + live,
            lastKnownFrames = counters.lastKnownFrames + lastKnown,
            unavailableFrames = counters.unavailableFrames + unavailable,
        )
        return views
    }

    @Synchronized
    fun counters(): DegradationCounters = counters

    @Synchronized
    fun resetCounters() {
        counters = DegradationCounters()
    }

    @Synchronized
    fun reset() {
        records.clear()
        counters = DegradationCounters()
    }
}

/** Normalisation used by the gauge and the simulation (raw vehicle units -> 0..1). */
object SignalScale {
    val speedKmh: ClosedFloatingPointRange<Double> = 0.0..180.0
    val batteryPct: ClosedFloatingPointRange<Double> = 0.0..100.0
    val rangeKm: ClosedFloatingPointRange<Double> = 0.0..600.0
    val hvacC: ClosedFloatingPointRange<Double> = 16.0..28.0

    fun normalize(signal: VehicleSignal, raw: Double): Double = when (signal) {
        is VehicleSignal.Speed -> scale(raw, speedKmh)
        is VehicleSignal.BatteryLevel -> scale(raw, batteryPct)
        is VehicleSignal.RangeKm -> scale(raw, rangeKm)
        is VehicleSignal.ChargePortOpen -> if (raw >= 0.5) 1.0 else 0.0
        is VehicleSignal.HvacTemp -> scale(raw, hvacC)
    }

    fun denormalize(signal: VehicleSignal, normalized: Double): Double = when (signal) {
        is VehicleSignal.Speed -> denormalize(normalized, speedKmh)
        is VehicleSignal.BatteryLevel -> denormalize(normalized, batteryPct)
        is VehicleSignal.RangeKm -> denormalize(normalized, rangeKm)
        is VehicleSignal.ChargePortOpen -> if (normalized >= 0.5) 1.0 else 0.0
        is VehicleSignal.HvacTemp -> denormalize(normalized, hvacC)
    }

    private fun scale(raw: Double, range: ClosedFloatingPointRange<Double>): Double =
        ((raw - range.start) / (range.endInclusive - range.start)).coerceIn(0.0, 1.0)

    private fun denormalize(normalized: Double, range: ClosedFloatingPointRange<Double>): Double =
        range.start + normalized.coerceIn(0.0, 1.0) * (range.endInclusive - range.start)
}
