package com.autocockpit.core.simulation

import kotlin.math.ceil

/** Percentile helper shared by the simulation report and the frame-time tracker semantics. */
object Stats {
    /** Neapolitan/nearest-rank percentile: the smallest value with at least p% of data at or below it. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        require(p in 0.0..100.0) { "percentile out of range: $p" }
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun percentile(sorted: LongArray, p: Double): Long = percentile(sorted.map { it.toDouble() }.sorted(), p).toLong()

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun summary(values: List<Long>): StatsSummary {
        if (values.isEmpty()) return StatsSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sorted = values.map { it.toDouble() }.sorted()
        return StatsSummary(
            count = values.size,
            mean = round2(mean(sorted)),
            p50 = round2(percentile(sorted, 50.0)),
            p95 = round2(percentile(sorted, 95.0)),
            p99 = round2(percentile(sorted, 99.0)),
            max = round2(sorted.last()),
        )
    }

    fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0
}
