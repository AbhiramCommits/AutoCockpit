package com.autocockpit.core.cli

import com.autocockpit.core.BuildInfo
import com.autocockpit.core.handoff.HandoffProtocol
import com.autocockpit.core.simulation.SimulationConfig
import com.autocockpit.core.simulation.SimulationHarness
import com.autocockpit.core.simulation.SimulationReport
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Entry point for the headless AutoCockpit tooling.
 *
 * Usage:
 *   ./gradlew :core:run --args="simulate --sessions 500 --seed 42 --out results/simulation.json"
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "version", null -> {
            println("${BuildInfo.APP_NAME} core ${BuildInfo.VERSION} (schema ${BuildInfo.SCHEMA_VERSION})")
            if (args.isEmpty()) printUsage()
        }
        "help", "--help", "-h" -> printUsage()
        "simulate" -> runSimulate(args.drop(1))
        else -> {
            System.err.println("unknown command: ${args.first()}")
            printUsage()
            exitProcess(2)
        }
    }
}

private fun printUsage() {
    println(
        """
        AutoCockpit headless tooling ${BuildInfo.VERSION}

        commands:
          version                          print version metadata
          simulate [options]               run the seeded simulation harness
          help                             print this message

        simulate options:
          --sessions N      sessions per loss rate (default 500)
          --seed S          RNG seed (default 42)
          --loss L[,L...]   chunk loss rates, e.g. 0,0.01,0.05,0.10
          --mtu N           requested ATT MTU (default 185)
          --tracks N        maximum tracks per session (default 500)
          --out PATH        JSON output path (default results/simulation.json)
        """.trimIndent(),
    )
}

private fun runSimulate(args: List<String>) {
    if (args.contains("--help")) {
        printUsage()
        return
    }
    var sessions = 500
    var seed = 42L
    var mtu = HandoffProtocol.REQUESTED_MTU
    var maxTracks = 500
    var out = "results/simulation.json"
    var lossRates = listOf(0.0, 0.01, 0.05, 0.10)

    var i = 0
    while (i < args.size) {
        when (val flag = args[i]) {
            "--sessions" -> sessions = args.requireValue(i++, flag).toInt()
            "--seed" -> seed = args.requireValue(i++, flag).toLong()
            "--mtu" -> mtu = args.requireValue(i++, flag).toInt()
            "--tracks" -> maxTracks = args.requireValue(i++, flag).toInt()
            "--loss" -> lossRates = args.requireValue(i++, flag).split(',')
                .filter { it.isNotBlank() }
                .map { it.trim().toDouble() }
            "--out" -> out = args.requireValue(i++, flag)
            else -> {
                System.err.println("unknown option: $flag")
                exitProcess(2)
            }
        }
        i++
    }

    val config = SimulationConfig(
        sessionsPerLossRate = sessions,
        seed = seed,
        mtu = mtu,
        maxTracks = maxTracks,
        lossRates = lossRates,
    )
    val harness = SimulationHarness(config)
    val startedAt = System.nanoTime()
    val report = harness.run()
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0

    val file = File(out)
    file.parentFile?.mkdirs()
    val json = Json { prettyPrint = true; encodeDefaults = true }
    file.writeText(json.encodeToString(report))

    printSummary(report, elapsedMs, file.path)
}

private fun List<String>.requireValue(index: Int, flag: String): String {
    if (index + 1 >= size) {
        System.err.println("missing value for $flag")
        exitProcess(2)
    }
    return this[index + 1]
}

private fun printSummary(report: SimulationReport, elapsedMs: Double, path: String) {
    val handoff = report.handoff
    println("AutoCockpit simulation ${report.build.version} (schema ${report.build.schemaVersion})")
    println("  seed ${report.config.seed}, ${report.config.sessionsPerLossRate} sessions x ${report.config.lossRates.size} loss rates = ${report.config.totalSessions} sessions")
    println("  wall time %.2f ms (%.2f us/session)".format(elapsedMs, elapsedMs * 1000.0 / report.config.totalSessions))
    println("  handoff success ${handoff.successRate}% (${handoff.succeeded}/${handoff.sessions}), retransmits mean ${handoff.chunkRetransmits.mean} p95 ${handoff.chunkRetransmits.p95}")
    println("  reconnect attempts mean ${handoff.meanReconnectAttempts.mean} p95 ${handoff.meanReconnectAttempts.p95}; recovery mean ${handoff.meanRecoveryLatencyMs.mean}ms p95 ${handoff.meanRecoveryLatencyMs.p95}ms")
    for (rate in handoff.byLossRate) {
        println("    loss %.0f%%: success %.2f%%, retransmits mean %.2f p95 %.0f, recovery mean %.2fms p95 %.0fms".format(
            rate.lossRate * 100, rate.successRate, rate.retransmits.mean, rate.retransmits.p95,
            rate.recoveryLatencyMs.mean, rate.recoveryLatencyMs.p95,
        ))
    }
    val degradation = report.degradation
    println("  degradation frames ${degradation.framesObserved}: LIVE ${degradation.livePct}% LAST_KNOWN ${degradation.lastKnownPct}% UNAVAILABLE ${degradation.unavailablePct}%")
    println("  payload 500 tracks: ${report.payload.fiveHundredTrackBytes} bytes, ${report.payload.fiveHundredTrackChunksAt20} chunks at the 20-byte ATT payload")
    println("  wrote $path")
}
