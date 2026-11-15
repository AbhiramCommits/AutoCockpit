package com.autocockpit.gauge.render

import android.view.Choreographer
import java.io.File
import kotlin.math.ceil
import kotlin.math.round

data class FrameStats(
    val count: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val jankFrames: Int,
    val jankPct: Double,
    val thresholdMs: Double,
    val windowCapacity: Int,
    val totalFramesObserved: Long,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"source\": \"FrameTimeTracker\",\n")
        append("  \"count\": $count,\n")
        append("  \"meanMs\": ${fmt(meanMs)},\n")
        append("  \"p50Ms\": ${fmt(p50Ms)},\n")
        append("  \"p95Ms\": ${fmt(p95Ms)},\n")
        append("  \"p99Ms\": ${fmt(p99Ms)},\n")
        append("  \"maxMs\": ${fmt(maxMs)},\n")
        append("  \"jankFrames\": $jankFrames,\n")
        append("  \"jankPct\": ${fmt(jankPct)},\n")
        append("  \"jankThresholdMs\": ${fmt(thresholdMs)},\n")
        append("  \"windowCapacity\": $windowCapacity,\n")
        append("  \"totalFramesObserved\": $totalFramesObserved\n")
        append("}")
    }
}

private fun fmt(value: Double): String = String.format("%.2f", value)

/** Pure percentile math, unit-tested against a fixture array. */
object FrameStatsCalculator {
    fun compute(
        samples: DoubleArray,
        count: Int,
        jankThresholdMs: Double,
        windowCapacity: Int,
        totalFramesObserved: Long,
    ): FrameStats {
        if (count == 0) {
            return FrameStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, jankThresholdMs, windowCapacity, totalFramesObserved)
        }
        val sorted = samples.copyOf(count).also { it.sort() }
        val sum = (0 until count).sumOf { samples[it] }
        val jank = (0 until count).count { samples[it] > jankThresholdMs }
        return FrameStats(
            count = count,
            meanMs = round2(sum / count),
            p50Ms = round2(percentile(sorted, 50.0)),
            p95Ms = round2(percentile(sorted, 95.0)),
            p99Ms = round2(percentile(sorted, 99.0)),
            maxMs = round2(sorted[count - 1]),
            jankFrames = jank,
            jankPct = round2(jank * 100.0 / count),
            thresholdMs = jankThresholdMs,
            windowCapacity = windowCapacity,
            totalFramesObserved = totalFramesObserved,
        )
    }

    fun percentile(sorted: DoubleArray, p: Double): Double {
        require(p in 0.0..100.0) { "percentile out of range: $p" }
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}

/**
 * Choreographer-based frame-time instrumentation with a rolling window.
 *
 * [recordFrame] is public so tests and the benchmark harness can feed deterministic deltas.
 * [exportJson] writes the current window to a file (used for `adb pull`).
 */
class FrameTimeTracker(
    private val windowCapacity: Int = 900,
    private val jankThresholdMs: Double = 1000.0 / 60.0,
) {
    private val window = DoubleArray(windowCapacity)
    private var writeIndex = 0
    private var count = 0
    private var totalFrames = 0L
    private var lastFrameNanos = 0L
    private var running = false
    private var callback: Choreographer.FrameCallback? = null

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                if (lastFrameNanos != 0L) {
                    recordFrame((frameTimeNanos - lastFrameNanos) / 1_000_000.0)
                }
                lastFrameNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        callback = frameCallback
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        callback = null
    }

    fun recordFrame(deltaMillis: Double) {
        if (deltaMillis < 0) return
        window[writeIndex] = deltaMillis
        writeIndex = (writeIndex + 1) % windowCapacity
        if (count < windowCapacity) count++
        totalFrames++
    }

    fun reset() {
        writeIndex = 0
        count = 0
        totalFrames = 0
        lastFrameNanos = 0
    }

    fun snapshot(): FrameStats = FrameStatsCalculator.compute(
        samples = window,
        count = count,
        jankThresholdMs = jankThresholdMs,
        windowCapacity = windowCapacity,
        totalFramesObserved = totalFrames,
    )

    fun exportJson(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(snapshot().toJson())
        return file
    }
}
