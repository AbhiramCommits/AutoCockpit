package com.autocockpit.core.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LinkStateMachineTest {

    private val policy = BackoffPolicy(baseMillis = 500, capMillis = 30_000, maxAttempts = 5, jitterFraction = 0.5)

    private fun seedSynced(machine: LinkStateMachine) {
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(185))
        machine.on(LinkEvent.SyncCompleted)
    }

    private fun syncedMachine(): LinkStateMachine {
        val machine = LinkStateMachine(policy, Random(7))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(185))
        machine.on(LinkEvent.SyncProgress(10, 40))
        machine.on(LinkEvent.SyncCompleted)
        return machine
    }

    @Test
    fun happyPathReachesSynced() {
        val machine = LinkStateMachine(policy, Random(1))
        assertEquals(LinkState.SCANNING, machine.on(LinkEvent.StartRequested).to)
        assertEquals(LinkState.CONNECTING, machine.on(LinkEvent.PeerDiscovered).to)
        assertEquals(LinkState.NEGOTIATING_MTU, machine.on(LinkEvent.Connected).to)
        val mtu = machine.on(LinkEvent.MtuNegotiated(185))
        assertEquals(LinkState.SYNCING, mtu.to)
        assertEquals(185, mtu.snapshot.mtu)
        assertTrue(mtu.effects.any { it is LinkEffect.StartSync })
        val done = machine.on(LinkEvent.SyncCompleted)
        assertEquals(LinkState.SYNCED, done.to)
        assertTrue(machine.snapshot.isSynced)
        assertEquals(0, machine.snapshot.attempt)
    }

    @Test
    fun scanTimeoutSchedulesBackoffReconnect() {
        val machine = LinkStateMachine(policy, Random(2))
        machine.on(LinkEvent.StartRequested)
        val transition = machine.on(LinkEvent.ScanTimedOut)
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals(TransitionKind.BACKOFF, transition.kind)
        assertEquals(1, machine.snapshot.attempt)
        val schedule = transition.effects.filterIsInstance<LinkEffect.ScheduleReconnect>().single()
        assertEquals(1, schedule.attempt)
        assertTrue(schedule.delayMillis in 250..750)
    }

    @Test
    fun reconnectTickReturnsToScanning() {
        val machine = LinkStateMachine(policy, Random(3))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.ScanTimedOut)
        val transition = machine.on(LinkEvent.ReconnectTick)
        assertEquals(LinkState.SCANNING, transition.to)
        assertEquals(TransitionKind.RECOVERY, transition.kind)
        assertTrue(transition.effects.contains(LinkEffect.BeginScan))
    }

    @Test
    fun connectFailedBacksOffAndIncrementsAttempt() {
        val machine = LinkStateMachine(policy, Random(4))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        val transition = machine.on(LinkEvent.ConnectFailed)
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals(1, machine.snapshot.attempt)
        assertTrue(transition.effects.any { it is LinkEffect.ScheduleReconnect })
        assertTrue(transition.effects.any { it is LinkEffect.AbortTransfer })
    }

    @Test
    fun lowNegotiatedMtuDegrades() {
        val machine = LinkStateMachine(policy, Random(5))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        val transition = machine.on(LinkEvent.MtuNegotiated(20))
        assertEquals(LinkState.DEGRADED, transition.to)
        assertTrue(transition.effects.any { it is LinkEffect.ScheduleReconnect })
    }

    @Test
    fun mtuNegotiationFailureDegrades() {
        val machine = LinkStateMachine(policy, Random(6))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        assertEquals(LinkState.DEGRADED, machine.on(LinkEvent.MtuNegotiationFailed).to)
    }

    @Test
    fun syncFailureDegradesWithReason() {
        val machine = LinkStateMachine(policy, Random(8))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(64))
        val transition = machine.on(LinkEvent.SyncFailed("chunk timeout"))
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals("chunk timeout", machine.snapshot.lastError)
    }

    @Test
    fun linkLossMidSyncResumesFromCurrentProgress() {
        val machine = LinkStateMachine(policy, Random(9))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(64))
        machine.on(LinkEvent.SyncProgress(120, 300))
        val transition = machine.on(LinkEvent.LinkLost)
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals(120, transition.snapshot.syncedChunks)
        assertEquals(300, transition.snapshot.totalChunks)
        assertTrue(transition.effects.filterIsInstance<LinkEffect.ScheduleReconnect>().isNotEmpty())
    }

    @Test
    fun schemaMismatchIsFatalWithoutRetry() {
        val machine = LinkStateMachine(policy, Random(10))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        val transition = machine.on(LinkEvent.SchemaMismatch(peerSchema = 2, localSchema = 1))
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals(TransitionKind.FATAL, transition.kind)
        assertTrue(machine.snapshot.exhausted)
        assertFalse(transition.effects.any { it is LinkEffect.ScheduleReconnect })
        assertTrue(transition.effects.any { it is LinkEffect.Notify })
    }

    @Test
    fun manualStartResetsAttemptsFromDegraded() {
        val machine = LinkStateMachine(policy, Random(11))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.ScanTimedOut)
        assertEquals(1, machine.snapshot.attempt)
        val transition = machine.on(LinkEvent.StartRequested)
        assertEquals(LinkState.SCANNING, transition.to)
        assertEquals(0, machine.snapshot.attempt)
        assertFalse(machine.snapshot.exhausted)
    }

    @Test
    fun resetFromAnyStateReturnsToDisconnected() {
        for (builder in listOf<(LinkStateMachine) -> Unit>(
            { it.on(LinkEvent.StartRequested) },
            { m ->
                m.on(LinkEvent.StartRequested)
                m.on(LinkEvent.PeerDiscovered)
            },
            { m ->
                m.on(LinkEvent.StartRequested)
                m.on(LinkEvent.PeerDiscovered)
                m.on(LinkEvent.Connected)
            },
        )) {
            val machine = LinkStateMachine(policy, Random(12))
            builder(machine)
            val transition = machine.on(LinkEvent.Reset)
            assertEquals(LinkState.DISCONNECTED, transition.to)
            assertEquals(TransitionKind.RESET, transition.kind)
            assertEquals(0, machine.snapshot.attempt)
        }
    }

    @Test
    fun giveUpExhaustsTheMachine() {
        val machine = syncedMachine()
        val transition = machine.on(LinkEvent.GiveUp)
        assertEquals(LinkState.DEGRADED, transition.to)
        assertEquals(TransitionKind.FATAL, transition.kind)
        assertTrue(machine.snapshot.exhausted)
        assertEquals(policy.maxAttempts, machine.snapshot.attempt)
    }

    @Test
    fun attemptsExhaustAfterMaxAttempts() {
        val machine = LinkStateMachine(policy, Random(13))
        machine.on(LinkEvent.StartRequested)
        repeat(policy.maxAttempts) { attempt ->
            val transition = machine.on(LinkEvent.ScanTimedOut)
            if (attempt < policy.maxAttempts - 1) {
                assertTrue(
                    "attempt $attempt should schedule a retry",
                    transition.effects.any { it is LinkEffect.ScheduleReconnect },
                )
                machine.on(LinkEvent.ReconnectTick)
            } else {
                assertFalse(transition.effects.any { it is LinkEffect.ScheduleReconnect })
                assertTrue(machine.snapshot.exhausted)
            }
        }
        assertEquals(policy.maxAttempts, machine.snapshot.attempt)
        val tick = machine.on(LinkEvent.ReconnectTick)
        assertEquals(TransitionKind.NOOP, tick.kind)
        assertTrue(tick.effects.any { it is LinkEffect.Notify })
    }

    @Test
    fun illegalEventsLeaveStateUntouched() {
        data class Case(val seedInto: (LinkStateMachine) -> Unit, val expected: LinkState, val event: LinkEvent)
        val cases = listOf(
            Case({}, LinkState.DISCONNECTED, LinkEvent.Connected),
            Case({}, LinkState.DISCONNECTED, LinkEvent.ScanTimedOut),
            Case({ it.on(LinkEvent.StartRequested) }, LinkState.SCANNING, LinkEvent.SyncCompleted),
            Case({ it.on(LinkEvent.StartRequested) }, LinkState.SCANNING, LinkEvent.MtuNegotiated(64)),
            Case({ m ->
                m.on(LinkEvent.StartRequested)
                m.on(LinkEvent.PeerDiscovered)
            }, LinkState.CONNECTING, LinkEvent.SyncCompleted),
            Case({ m ->
                m.on(LinkEvent.StartRequested)
                m.on(LinkEvent.PeerDiscovered)
            }, LinkState.CONNECTING, LinkEvent.MtuNegotiated(64)),
            Case({
                    m ->
                m.on(LinkEvent.StartRequested)
                m.on(LinkEvent.PeerDiscovered)
                m.on(LinkEvent.Connected)
            }, LinkState.NEGOTIATING_MTU, LinkEvent.Connected),
            Case(::seedSynced, LinkState.SYNCED, LinkEvent.SyncCompleted),
            Case(::seedSynced, LinkState.SYNCED, LinkEvent.ReconnectTick),
        )
        for (case in cases) {
            val machine = LinkStateMachine(policy, Random(14))
            case.seedInto(machine)
            assertEquals(case.expected, machine.snapshot.state)
            val transition = machine.on(case.event)
            assertEquals(TransitionKind.ILLEGAL, transition.kind)
            assertEquals(case.expected, transition.to)
            assertTrue(transition.effects.isEmpty())
            assertEquals(case.expected, machine.snapshot.state)
        }
    }

    @Test
    fun syncProgressIsANoopThatTracksProgress() {
        val machine = LinkStateMachine(policy, Random(15))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(64))
        val transition = machine.on(LinkEvent.SyncProgress(500, 1000))
        assertEquals(TransitionKind.NOOP, transition.kind)
        assertEquals(LinkState.SYNCING, machine.snapshot.state)
        assertEquals(0.5f, machine.snapshot.progress)
    }

    @Test
    fun syncCompletedResetsAttemptsAfterReconnect() {
        val machine = LinkStateMachine(policy, Random(16))
        machine.on(LinkEvent.StartRequested)
        machine.on(LinkEvent.ScanTimedOut)
        machine.on(LinkEvent.ReconnectTick)
        machine.on(LinkEvent.PeerDiscovered)
        machine.on(LinkEvent.Connected)
        machine.on(LinkEvent.MtuNegotiated(64))
        machine.on(LinkEvent.SyncCompleted)
        assertEquals(LinkState.SYNCED, machine.snapshot.state)
        assertEquals(0, machine.snapshot.attempt)
        assertFalse(machine.snapshot.exhausted)
    }

    @Test
    fun transitionCounterTracksForwardSteps() {
        val machine = syncedMachine()
        // start, discover, connect, mtu, sync-complete (progress is a NOOP) = 5 state changes
        assertEquals(5, machine.snapshot.transitions)
        assertEquals(6, machine.history().size)
        assertTrue(machine.history().all { it.kind != TransitionKind.ILLEGAL })
    }

    @Test
    fun historyIsBounded() {
        val machine = LinkStateMachine(policy, Random(17))
        repeat(LinkStateMachine.HISTORY_LIMIT * 2) { machine.on(LinkEvent.Reset) }
        assertEquals(LinkStateMachine.HISTORY_LIMIT, machine.history().size)
    }

    @Test
    fun backoffIsMonotonicUntilTheCap() {
        val backoff = BackoffPolicy(baseMillis = 500, capMillis = 8_000, maxAttempts = 10)
        var previous = 0L
        for (attempt in 1..10) {
            val delay = backoff.baseDelayFor(attempt)
            assertTrue("attempt $attempt: $delay must be >= $previous", delay >= previous)
            previous = delay
        }
        assertEquals(8_000L, backoff.baseDelayFor(10))
    }

    @Test
    fun backoffJitterStaysWithinBounds() {
        val backoff = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, maxAttempts = 8, jitterFraction = 0.5)
        for (attempt in 1..8) {
            val base = backoff.baseDelayFor(attempt)
            assertEquals(base, backoff.delayFor(attempt, 0.5))
            assertTrue(backoff.delayFor(attempt, 0.0) in (base / 2)..base)
            assertTrue(backoff.delayFor(attempt, 1.0) in base..(base * 3 / 2 + 1))
        }
    }

    @Test
    fun backoffRejectsInvalidConfiguration() {
        assertTrue(runCatching { BackoffPolicy(baseMillis = 0) }.isFailure)
        assertTrue(runCatching { BackoffPolicy(baseMillis = 1000, capMillis = 500) }.isFailure)
        assertTrue(runCatching { BackoffPolicy(jitterFraction = 1.5) }.isFailure)
        assertTrue(runCatching { BackoffPolicy(baseMillis = 500).delayFor(1, 1.5) }.isFailure)
    }

    @Test
    fun seededJitterIsDeterministic() {
        fun delays(): List<Long> {
            val machine = LinkStateMachine(policy, Random(99))
            machine.on(LinkEvent.StartRequested)
            val out = ArrayList<Long>()
            repeat(4) {
                val transition = machine.on(LinkEvent.ScanTimedOut)
                transition.effects.filterIsInstance<LinkEffect.ScheduleReconnect>()
                    .firstOrNull()?.let { out += it.delayMillis }
                machine.on(LinkEvent.ReconnectTick)
            }
            return out
        }
        assertEquals(delays(), delays())
        assertNotNull(delays())
    }

    @Test
    fun progressIsClampedForReportRendering() {
        val snapshot = LinkSnapshot(totalChunks = 10, syncedChunks = 25)
        assertEquals(1f, snapshot.progress)
        assertEquals(0f, LinkSnapshot().progress)
    }
}
