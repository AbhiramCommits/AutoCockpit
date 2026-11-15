package com.autocockpit.core.simulation

import kotlinx.serialization.Serializable

@Serializable
data class StatsSummary(
    val count: Int,
    val mean: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
)

@Serializable
data class BuildSection(
    val app: String,
    val version: String,
    val schemaVersion: Int,
    val jvm: String,
    val os: String,
)

@Serializable
data class ConfigSection(
    val seed: Long,
    val sessionsPerLossRate: Int,
    val totalSessions: Int,
    val lossRates: List<Double>,
    val mtu: Int,
    val chunkPayloadBytes: Int,
    val tracksPerSession: String,
    val staleAfterMillis: Long,
    val unavailableAfterMillis: Long,
    val simulatedClockEpochMillis: Long,
)

@Serializable
data class FaultCounters(
    val propertyDropouts: Int,
    val staleSamples: Int,
    val propertyErrors: Int,
    val bleDisconnects: Int,
    val chunkLosses: Int,
    val crcCorruptions: Int,
    val duplicateChunks: Int,
    val outOfOrderChunks: Int,
    val reconnectExhaustions: Int,
    val schemaAborts: Int,
)

@Serializable
data class LossRateHandoff(
    val lossRate: Double,
    val sessions: Int,
    val succeeded: Int,
    val successRate: Double,
    val reconnectSessions: Int,
    val retransmits: StatsSummary,
    val reconnectAttempts: StatsSummary,
    val recoveryLatencyMs: StatsSummary,
    val bytesOnWire: StatsSummary,
)

@Serializable
data class HandoffSection(
    val sessions: Int,
    val succeeded: Int,
    val successRate: Double,
    val meanReconnectAttempts: StatsSummary,
    val meanRecoveryLatencyMs: StatsSummary,
    val chunkRetransmits: StatsSummary,
    val sessionsWithReconnect: Int,
    val byLossRate: List<LossRateHandoff>,
    val maxPayloadBytes: Int,
    val maxPayloadTracks: Int,
    val bytesOnWire500Tracks: Int,
    val chunks500Tracks: Int,
    val frameHeaderBytes: Int,
    val chunkHeaderBytes: Int,
)

@Serializable
data class DegradationSection(
    val sessions: Int,
    val framesObserved: Int,
    val liveFrames: Int,
    val lastKnownFrames: Int,
    val unavailableFrames: Int,
    val livePct: Double,
    val lastKnownPct: Double,
    val unavailablePct: Double,
    val recoveryLatencyMs: StatsSummary,
)

@Serializable
data class PayloadSection(
    val oneTrackBytes: Int,
    val tenTrackBytes: Int,
    val hundredTrackBytes: Int,
    val fiveHundredTrackBytes: Int,
    val fiveHundredTrackChunksAt20: Int,
)

@Serializable
data class SimulationReport(
    val build: BuildSection,
    val config: ConfigSection,
    val faults: FaultCounters,
    val handoff: HandoffSection,
    val degradation: DegradationSection,
    val payload: PayloadSection,
)
