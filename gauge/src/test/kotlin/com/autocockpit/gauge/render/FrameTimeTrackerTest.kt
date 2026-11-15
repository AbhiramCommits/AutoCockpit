package com.autocockpit.gauge.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FrameTimeTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixture = (16..30).map { it.toDouble() } // 16..30 inclusive, 15 frames

    @Test
    fun percentileMathMatchesTheKnownFixture() {
        val stats = FrameStatsCalculator.compute(
            samples = fixture.toDoubleArray(),
            count = fixture.size,
            jankThresholdMs = 16.67,
            windowCapacity = 900,
            totalFramesObserved = 15,
        )
        assertEquals(15, stats.count)
        assertEquals(23.0, stats.meanMs, 0.001)
        assertEquals(23.0, stats.p50Ms, 0.001)
        // Nearest-rank p95 over 15 samples is the 15th value (30), not an interpolation.
        assertEquals(30.0, stats.p95Ms, 0.001)
        assertEquals(30.0, stats.p99Ms, 0.001)
        assertEquals(30.0, stats.maxMs, 0.001)
        assertEquals(14, stats.jankFrames) // 17..30 exceed 16.67
        assertEquals(93.33, stats.jankPct, 0.01)
    }

    @Test
    fun nearestRankPercentileOnSortedInput() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, FrameStatsCalculator.percentile(values, 0.0), 0.0)
        assertEquals(3.0, FrameStatsCalculator.percentile(values, 50.0), 0.0)
        assertEquals(5.0, FrameStatsCalculator.percentile(values, 95.0), 0.0)
        assertEquals(5.0, FrameStatsCalculator.percentile(values, 100.0), 0.0)
    }

    @Test
    fun emptyWindowReportsZeros() {
        val stats = FrameTimeTracker().snapshot()
        assertEquals(0, stats.count)
        assertEquals(0.0, stats.meanMs, 0.0)
        assertEquals(0.0, stats.jankPct, 0.0)
    }

    @Test
    fun rollingWindowKeepsOnlyTheMostRecentSamples() {
        val tracker = FrameTimeTracker(windowCapacity = 10)
        repeat(25) { tracker.recordFrame(10.0 + it) }
        val stats = tracker.snapshot()
        assertEquals(10, stats.count)
        assertEquals(25, stats.totalFramesObserved.toInt())
        // Last ten samples are 25.0..34.0 (values 15..24 + 10).
        assertEquals(34.0, stats.maxMs, 0.001)
        assertTrue(stats.meanMs > 28.0)
    }

    @Test
    fun jankThresholdIsStrictlyGreaterThan() {
        val tracker = FrameTimeTracker(jankThresholdMs = 16.67)
        tracker.recordFrame(16.67)
        tracker.recordFrame(16.68)
        val stats = tracker.snapshot()
        assertEquals(1, stats.jankFrames)
        assertEquals(50.0, stats.jankPct, 0.001)
    }

    @Test
    fun exportWritesValidJson() {
        val tracker = FrameTimeTracker()
        repeat(60) { tracker.recordFrame(if (it % 10 == 0) 20.0 else 14.0) }
        val file = File(tempFolder.newFolder(), "frames/frame_metrics.json")
        tracker.exportJson(file)
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.contains("\"count\": 60"))
        assertTrue(text.contains("\"source\": \"FrameTimeTracker\""))
        assertTrue(text.contains("\"jankPct\""))
        assertTrue(text.trim().startsWith("{"))
        assertTrue(text.trim().endsWith("}"))
    }

    @Test
    fun negativeDeltasAreIgnored() {
        val tracker = FrameTimeTracker()
        tracker.recordFrame(-5.0)
        assertEquals(0, tracker.snapshot().count)
    }

    @Test
    fun resetClearsTheWindow() {
        val tracker = FrameTimeTracker()
        repeat(5) { tracker.recordFrame(16.0) }
        tracker.reset()
        assertEquals(0, tracker.snapshot().count)
        assertEquals(0, tracker.snapshot().totalFramesObserved.toInt())
    }

    @Test
    fun meanUsesEverySampleNotJustTheMaximum() {
        val tracker = FrameTimeTracker()
        tracker.recordFrame(10.0)
        tracker.recordFrame(20.0)
        tracker.recordFrame(30.0)
        assertEquals(20.0, tracker.snapshot().meanMs, 0.001)
    }
}
