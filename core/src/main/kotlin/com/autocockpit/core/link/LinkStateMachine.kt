package com.autocockpit.core.link

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** Companion link lifecycle. */
enum class LinkState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    NEGOTIATING_MTU,
    SYNCING,
    SYNCED,
    DEGRADED,
}

/** Events the link layer consumes. Produced by the BLE callbacks and by timers. */
sealed interface LinkEvent {
    data object StartRequested : LinkEvent
    data object Reset : LinkEvent
    data object PeerDiscovered : LinkEvent
    data object ScanTimedOut : LinkEvent
    data object Connected : LinkEvent
    data object ConnectFailed : LinkEvent
    data class MtuNegotiated(val mtu: Int) : LinkEvent
    data object MtuNegotiationFailed : LinkEvent
    data class SyncProgress(val ackedChunks: Int, val totalChunks: Int) : LinkEvent
    data object SyncCompleted : LinkEvent
    data class SyncFailed(val reason: String) : LinkEvent
    data object LinkLost : LinkEvent
    data object ReconnectTick : LinkEvent
    data object GiveUp : LinkEvent
    data class SchemaMismatch(val peerSchema: Int, val localSchema: Int) : LinkEvent
}

/** Side effects the owner must execute after a transition. The reducer never performs IO. */
sealed interface LinkEffect {
    data object BeginScan : LinkEffect
    data object StopScan : LinkEffect
    data object Connect : LinkEffect
    data object RequestMtu : LinkEffect
    data class StartSync(val resumeFromChunk: Int) : LinkEffect
    data class ScheduleReconnect(val delayMillis: Long, val attempt: Int) : LinkEffect
    data class AbortTransfer(val reason: String) : LinkEffect
    data class Notify(val message: String) : LinkEffect
    data object SyncDone : LinkEffect
}

enum class TransitionKind {
    /** Event is not legal in this state; snapshot untouched. */
    ILLEGAL,

    /** Legal event that leaves the state unchanged (progress, bookkeeping). */
    NOOP,

    /** Normal forward step. */
    FORWARD,

    /** Failure handled with a scheduled retry. */
    BACKOFF,

    /** Retrying succeeded and the machine left DEGRADED. */
    RECOVERY,

    /** Hard protocol failure; automatic retries stop. */
    FATAL,

    /** Explicit reset. */
    RESET,
}

data class LinkSnapshot(
    val state: LinkState = LinkState.DISCONNECTED,
    val attempt: Int = 0,
    val mtu: Int = 20,
    val syncedChunks: Int = 0,
    val totalChunks: Int = 0,
    val lastError: String? = null,
    val transitions: Int = 0,
    val exhausted: Boolean = false,
) {
    val isSynced: Boolean get() = state == LinkState.SYNCED
    val progress: Float
        get() = if (totalChunks <= 0) 0f else (syncedChunks.toFloat() / totalChunks).coerceIn(0f, 1f)
}

data class Transition(
    val from: LinkState,
    val to: LinkState,
    val kind: TransitionKind,
    val snapshot: LinkSnapshot,
    val effects: List<LinkEffect> = emptyList(),
    val event: LinkEvent,
)

/**
 * Exponential backoff with jitter. Pure: the caller supplies a jitter draw in `[0, 1)` so tests
 * and the simulation stay deterministic.
 */
data class BackoffPolicy(
    val baseMillis: Long = 500L,
    val capMillis: Long = 30_000L,
    val maxAttempts: Int = 5,
    /** Fraction of the exponential step used as symmetric jitter, 0..1. */
    val jitterFraction: Double = 0.5,
) {
    init {
        require(baseMillis > 0) { "baseMillis must be positive" }
        require(capMillis >= baseMillis) { "capMillis must be >= baseMillis" }
        require(maxAttempts in 1..64) { "maxAttempts out of range" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be within 0..1" }
    }

    /** Un-jittered exponential step for [attempt] (1-based), capped. */
    fun baseDelayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        val exponent = (attempt - 1).coerceAtMost(30)
        val raw = baseMillis.toDouble() * 2.0.pow(exponent)
        return min(capMillis.toDouble(), raw).roundToLong()
    }

    /** Jittered delay for [attempt]; [jitterUnit] must be in `[0, 1]`. */
    fun delayFor(attempt: Int, jitterUnit: Double): Long {
        require(jitterUnit in 0.0..1.0) { "jitterUnit outside 0..1: $jitterUnit" }
        val base = baseDelayFor(attempt)
        val span = base * jitterFraction
        val low = base - span
        val high = base + span
        return (low + (high - low) * jitterUnit).roundToLong().coerceIn(1L, capMillis * 2)
    }
}

/**
 * Pure reducer for the companion link. `reduce` is a function of
 * `(snapshot, event, jitter, policy)`; [LinkStateMachine] only stores the latest snapshot and
 * supplies jitter, so the transition table is fully testable without threads or coroutines.
 */
object LinkReducer {
    fun reduce(
        current: LinkSnapshot,
        event: LinkEvent,
        policy: BackoffPolicy,
        jitterUnit: Double,
    ): Transition {
        val state = current.state
        if (event is LinkEvent.Reset) {
            return Transition(
                from = state,
                to = LinkState.DISCONNECTED,
                kind = TransitionKind.RESET,
                snapshot = LinkSnapshot(),
                effects = listOf(LinkEffect.StopScan),
                event = event,
            )
        }
        if (event is LinkEvent.StartRequested) {
            val next = current.copy(
                state = LinkState.SCANNING,
                attempt = 0,
                syncedChunks = 0,
                totalChunks = 0,
                lastError = null,
                exhausted = false,
                transitions = current.transitions + 1,
            )
            return Transition(state, LinkState.SCANNING, TransitionKind.RECOVERY, next, listOf(LinkEffect.BeginScan), event)
        }
        if (event is LinkEvent.GiveUp) {
            val next = current.copy(
                state = LinkState.DEGRADED,
                attempt = policy.maxAttempts,
                exhausted = true,
                lastError = "gave up after ${policy.maxAttempts} attempts",
            )
            return Transition(
                from = state,
                to = LinkState.DEGRADED,
                kind = TransitionKind.FATAL,
                snapshot = next,
                effects = listOf(LinkEffect.AbortTransfer("give up"), LinkEffect.StopScan),
                event = event,
            )
        }
        if (event is LinkEvent.SchemaMismatch) {
            val message = "schema mismatch: peer ${event.peerSchema}, local ${event.localSchema}"
            val next = current.copy(
                state = LinkState.DEGRADED,
                attempt = policy.maxAttempts,
                exhausted = true,
                lastError = message,
            )
            return Transition(
                from = state,
                to = LinkState.DEGRADED,
                kind = TransitionKind.FATAL,
                snapshot = next,
                effects = listOf(LinkEffect.AbortTransfer(message), LinkEffect.Notify(message)),
                event = event,
            )
        }

        return when (state) {
            LinkState.DISCONNECTED -> illegal(current, event)
            LinkState.SCANNING -> when (event) {
                is LinkEvent.PeerDiscovered -> forward(
                    current,
                    LinkState.CONNECTING,
                    event,
                    listOf(LinkEffect.StopScan, LinkEffect.Connect),
                )
                is LinkEvent.ScanTimedOut -> backoff(current, policy, jitterUnit, event, "scan timed out")
                else -> illegal(current, event)
            }
            LinkState.CONNECTING -> when (event) {
                is LinkEvent.Connected -> forward(current, LinkState.NEGOTIATING_MTU, event, listOf(LinkEffect.RequestMtu))
                is LinkEvent.ConnectFailed -> backoff(current, policy, jitterUnit, event, "connect failed")
                is LinkEvent.LinkLost -> backoff(current, policy, jitterUnit, event, "link lost while connecting")
                else -> illegal(current, event)
            }
            LinkState.NEGOTIATING_MTU -> when (event) {
                is LinkEvent.MtuNegotiated -> if (event.mtu >= 23) {
                    val next = current.copy(
                        state = LinkState.SYNCING,
                        mtu = event.mtu,
                        transitions = current.transitions + 1,
                    )
                    Transition(
                        from = state,
                        to = LinkState.SYNCING,
                        kind = TransitionKind.FORWARD,
                        snapshot = next,
                        effects = listOf(LinkEffect.StartSync(resumeFromChunk = current.syncedChunks)),
                        event = event,
                    )
                } else {
                    backoff(
                        current,
                        policy,
                        jitterUnit,
                        event,
                        "negotiated mtu ${event.mtu} below the BLE minimum 23",
                    )
                }
                is LinkEvent.MtuNegotiationFailed -> backoff(current, policy, jitterUnit, event, "mtu negotiation failed")
                is LinkEvent.LinkLost -> backoff(current, policy, jitterUnit, event, "link lost during mtu negotiation")
                else -> illegal(current, event)
            }
            LinkState.SYNCING -> when (event) {
                is LinkEvent.SyncProgress -> {
                    val next = current.copy(
                        syncedChunks = event.ackedChunks.coerceAtLeast(0),
                        totalChunks = event.totalChunks.coerceAtLeast(0),
                    )
                    Transition(state, state, TransitionKind.NOOP, next, emptyList(), event)
                }
                is LinkEvent.SyncCompleted -> {
                    val next = current.copy(
                        state = LinkState.SYNCED,
                        attempt = 0,
                        exhausted = false,
                        lastError = null,
                        syncedChunks = if (current.totalChunks > 0) current.totalChunks else current.syncedChunks,
                        transitions = current.transitions + 1,
                    )
                    Transition(state, LinkState.SYNCED, TransitionKind.FORWARD, next, listOf(LinkEffect.SyncDone), event)
                }
                is LinkEvent.SyncFailed -> backoff(current, policy, jitterUnit, event, event.reason)
                is LinkEvent.LinkLost -> backoff(current, policy, jitterUnit, event, "link lost mid-transfer")
                else -> illegal(current, event)
            }
            LinkState.SYNCED -> when (event) {
                is LinkEvent.LinkLost -> backoff(current, policy, jitterUnit, event, "link lost after sync")
                is LinkEvent.SyncProgress -> illegal(current, event)
                else -> illegal(current, event)
            }
            LinkState.DEGRADED -> when (event) {
                is LinkEvent.ReconnectTick -> {
                    if (current.exhausted || current.attempt >= policy.maxAttempts) {
                        val next = current.copy(exhausted = true)
                        Transition(
                            from = state,
                            to = LinkState.DEGRADED,
                            kind = TransitionKind.NOOP,
                            snapshot = next,
                            effects = listOf(
                                LinkEffect.Notify("reconnect budget exhausted (${current.attempt}/${policy.maxAttempts})"),
                            ),
                            event = event,
                        )
                    } else {
                        val next = current.copy(state = LinkState.SCANNING, lastError = current.lastError)
                        Transition(
                            state,
                            LinkState.SCANNING,
                            TransitionKind.RECOVERY,
                            next,
                            listOf(LinkEffect.BeginScan),
                            event,
                        )
                    }
                }
                is LinkEvent.LinkLost -> backoff(current, policy, jitterUnit, event, "link lost while degraded")
                else -> illegal(current, event)
            }
        }
    }

    private fun forward(
        current: LinkSnapshot,
        to: LinkState,
        event: LinkEvent,
        effects: List<LinkEffect>,
    ): Transition {
        val next = current.copy(state = to, transitions = current.transitions + 1)
        return Transition(current.state, to, TransitionKind.FORWARD, next, effects, event)
    }

    private fun illegal(current: LinkSnapshot, event: LinkEvent): Transition =
        Transition(current.state, current.state, TransitionKind.ILLEGAL, current, emptyList(), event)

    /** Failure path: increment the attempt counter and either schedule a retry or exhaust. */
    private fun backoff(
        current: LinkSnapshot,
        policy: BackoffPolicy,
        jitterUnit: Double,
        event: LinkEvent,
        reason: String,
    ): Transition {
        val attempts = current.attempt + 1
        val exhausted = attempts >= policy.maxAttempts
        val delay = policy.delayFor(attempts.coerceAtMost(policy.maxAttempts), jitterUnit)
        val effects = buildList {
            add(LinkEffect.AbortTransfer(reason))
            if (exhausted) {
                add(LinkEffect.Notify("giving up after $attempts attempts: $reason"))
            } else {
                add(LinkEffect.ScheduleReconnect(delay, attempts))
            }
        }
        val next = current.copy(
            state = LinkState.DEGRADED,
            attempt = attempts.coerceAtMost(policy.maxAttempts),
            exhausted = exhausted,
            lastError = reason,
            transitions = current.transitions + if (current.state != LinkState.DEGRADED) 1 else 0,
        )
        return Transition(current.state, LinkState.DEGRADED, TransitionKind.BACKOFF, next, effects, event)
    }
}

/**
 * Stateful facade over [LinkReducer]. No coroutines and no IO: effects are returned to the caller
 * which owns the BLE stack and its timers.
 */
class LinkStateMachine(
    private val policy: BackoffPolicy = BackoffPolicy(),
    private val jitter: Random = Random(42),
) {
    var snapshot: LinkSnapshot = LinkSnapshot()
        private set

    /** Every transition is retained so the settings screen can render attempt history. */
    private val history = ArrayDeque<Transition>()

    fun on(event: LinkEvent): Transition {
        val transition = LinkReducer.reduce(snapshot, event, policy, jitter.nextDouble())
        snapshot = transition.snapshot
        history.addLast(transition)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        return transition
    }

    fun history(): List<Transition> = history.toList()

    /** Retry budget from the configured policy (used by the BLE client). */
    val maxAttempts: Int get() = policy.maxAttempts

    fun reset() {
        on(LinkEvent.Reset)
    }

    companion object {
        const val HISTORY_LIMIT: Int = 64
    }
}
