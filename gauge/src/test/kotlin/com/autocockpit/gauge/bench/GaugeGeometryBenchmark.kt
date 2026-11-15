package com.autocockpit.gauge.bench

import com.autocockpit.gauge.math.ArcGaugeSpec
import com.autocockpit.gauge.math.GaugeMesh
import java.io.File

/**
 * Hand-rolled JVM micro-benchmark for arc tessellation. No benchmarking framework: a warmup round,
 * then timed rounds that run for a fixed wall-clock budget, reporting vertices/ms and ns/update at
 * 64/256/1024 segments. Called by GaugeGeometryBenchmarkTest and runnable standalone via `main`.
 */
object GaugeGeometryBenchmark {

    private const val WARMUP_ITERATIONS = 20_000
    private const val TIMED_BUDGET_MS = 400L

    data class Result(
        val segments: Int,
        val verticesPerUpdate: Int,
        val iterations: Int,
        val elapsedMs: Double,
        val nanosPerUpdate: Double,
        val verticesPerMs: Double,
        val allocationCount: Int,
    )

    fun run(outDir: File): List<Result> {
        outDir.mkdirs()
        val results = listOf(64, 256, 1024).map(::measure)
        File(outDir, "geometry_benchmark.json").writeText(json(results))
        return results
    }

    private fun json(results: List<Result>): String = buildString {
        append("{\n")
        append("  \"source\": \"GaugeGeometryBenchmark\",\n")
        append("  \"jvm\": \"${System.getProperty("java.version")}\",\n")
        append("  \"os\": \"${System.getProperty("os.name")} ${System.getProperty("os.arch")}\",\n")
        append("  \"warmupIterations\": $WARMUP_ITERATIONS,\n")
        append("  \"timedBudgetMs\": $TIMED_BUDGET_MS,\n")
        append("  \"results\": [\n")
        results.forEachIndexed { index, r ->
            append("    {\n")
            append("      \"segments\": ${r.segments},\n")
            append("      \"verticesPerUpdate\": ${r.verticesPerUpdate},\n")
            append("      \"iterations\": ${r.iterations},\n")
            append("      \"elapsedMs\": ${fmt(r.elapsedMs)},\n")
            append("      \"nanosPerUpdate\": ${fmt(r.nanosPerUpdate)},\n")
            append("      \"verticesPerMs\": ${fmt(r.verticesPerMs)},\n")
            append("      \"allocationCount\": ${r.allocationCount}\n")
            append("    }${if (index == results.lastIndex) "" else ","}\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun measure(segments: Int): Result {
        val spec = ArcGaugeSpec(segments = segments)
        val mesh = GaugeMesh.create(spec)
        repeat(WARMUP_ITERATIONS) { mesh.update((it % 100) / 100f) }

        var iterations = 0
        var value = 0f
        val start = System.nanoTime()
        var elapsedNanos: Long
        do {
            mesh.update(value)
            value += 0.01f
            if (value > 1f) value = 0f
            iterations++
            elapsedNanos = System.nanoTime() - start
        } while (elapsedNanos < TIMED_BUDGET_MS * 1_000_000L)

        val elapsedMs = elapsedNanos / 1_000_000.0
        return Result(
            segments = segments,
            verticesPerUpdate = mesh.vertexCount(),
            iterations = iterations,
            elapsedMs = elapsedMs,
            nanosPerUpdate = elapsedNanos.toDouble() / iterations,
            verticesPerMs = iterations.toDouble() * mesh.vertexCount() / elapsedMs,
            allocationCount = mesh.allocationCount,
        )
    }

    private fun fmt(value: Double): String = String.format("%.3f", value)

    fun report(results: List<Result>): String = buildString {
        appendLine("GaugeGeometry tessellation benchmark")
        appendLine("  segments  vertices  ns/update  vertices/ms  allocations")
        results.forEach { r ->
            appendLine(
                "  %8d  %8d  %9.1f  %11.1f  %11d".format(
                    r.segments,
                    r.verticesPerUpdate,
                    r.nanosPerUpdate,
                    r.verticesPerMs,
                    r.allocationCount,
                ),
            )
        }
    }
}

fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "results")
    val results = GaugeGeometryBenchmark.run(outDir)
    print(GaugeGeometryBenchmark.report(results))
    println("wrote ${File(outDir, "geometry_benchmark.json").path}")
}
