package com.autocockpit.core.simulation

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {
    @Test
    fun percentileUsesNearestRank() {
        val values = (1..10).map { it.toDouble() }
        assertEquals(1.0, Stats.percentile(values, 0.0), 0.0)
        assertEquals(5.0, Stats.percentile(values, 50.0), 0.0)
        assertEquals(5.0, Stats.percentile(values, 41.0), 0.0)
        assertEquals(10.0, Stats.percentile(values, 95.0), 0.0)
        assertEquals(10.0, Stats.percentile(values, 100.0), 0.0)
    }

    @Test
    fun summaryIgnoresEmptyInput() {
        val summary = Stats.summary(emptyList())
        assertEquals(0, summary.count)
        assertEquals(0.0, summary.mean, 0.0)
        assertEquals(0.0, summary.max, 0.0)
    }

    @Test
    fun summaryComputesMeanAndMax() {
        val summary = Stats.summary(listOf(10L, 20L, 30L, 40L))
        assertEquals(4, summary.count)
        assertEquals(25.0, summary.mean, 0.0)
        assertEquals(40.0, summary.max, 0.0)
        assertEquals(40.0, summary.p95, 0.0)
    }

    @Test
    fun percentileOfSingleValueIsThatValue() {
        assertEquals(42.0, Stats.percentile(listOf(42.0), 99.0), 0.0)
    }
}
