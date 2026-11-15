package com.autocockpit.gauge.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the micro-benchmark inside the normal unit-test task so every `:gauge:test` produces a
 * fresh results/geometry_benchmark.json.
 */
class GaugeGeometryBenchmarkTest {

    @Test
    fun tessellationThroughputIsMeasured() {
        val outDir = File(System.getProperty("autocockpit.resultsDir") ?: "results")
        val results = GaugeGeometryBenchmark.run(outDir)
        assertEquals(listOf(64, 256, 1024), results.map { it.segments })
        for (result in results) {
            assertTrue("no iterations for ${result.segments}", result.iterations > 100)
            assertTrue("no throughput for ${result.segments}", result.verticesPerMs > 0.0)
            assertTrue("unexpected allocations", result.allocationCount == 1)
        }
        val json = File(outDir, "geometry_benchmark.json").readText()
        assertTrue(json.contains("\"verticesPerMs\""))
        assertTrue(json.contains("\"segments\": 1024"))
        println(GaugeGeometryBenchmark.report(results))
    }
}
