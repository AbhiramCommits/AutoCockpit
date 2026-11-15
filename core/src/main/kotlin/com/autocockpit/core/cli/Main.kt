package com.autocockpit.core.cli

import com.autocockpit.core.BuildInfo
import kotlin.system.exitProcess

/**
 * Entry point for the headless AutoCockpit tooling.
 *
 * Usage:
 *   ./gradlew :core:run --args="version"
 *   ./gradlew :core:run --args="simulate --sessions 500 --seed 42 --out results/simulation.json"
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "version", null -> {
            println("${BuildInfo.APP_NAME} core ${BuildInfo.VERSION} (schema ${BuildInfo.SCHEMA_VERSION})")
        }
        "help", "--help", "-h" -> printUsage()
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
          version                                  print version metadata
          help                                     print this message
        """.trimIndent(),
    )
}
