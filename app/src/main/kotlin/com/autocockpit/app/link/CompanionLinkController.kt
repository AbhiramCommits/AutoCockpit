package com.autocockpit.app.link

import com.autocockpit.core.link.BackoffPolicy
import com.autocockpit.core.link.LinkEvent
import com.autocockpit.core.link.LinkSnapshot
import com.autocockpit.core.link.LinkState
import com.autocockpit.core.link.LinkStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Owns the companion link state machine for the UI. Step 5 wires the BLE GATT callbacks into
 * [onEvent]; until a phone is present the cockpit still exposes a real state machine, attempt
 * counter and reconnect path (no fake "connected" state).
 */
class CompanionLinkController(
    private val machine: LinkStateMachine = LinkStateMachine(
        BackoffPolicy(baseMillis = 300, capMillis = 8_000, maxAttempts = 4),
        Random(7),
    ),
) {
    private val _state = MutableStateFlow(machine.snapshot)
    val state: StateFlow<LinkSnapshot> = _state.asStateFlow()

    fun onEvent(event: LinkEvent): LinkSnapshot {
        val transition = machine.on(event)
        _state.value = transition.snapshot
        return transition.snapshot
    }

    /**
     * Re-applies the last committed handoff through the link state machine. Returns false when
     * there is nothing to re-apply (the machine then degrades with a visible reason).
     */
    fun resync(reapply: () -> Boolean): LinkSnapshot {
        onEvent(LinkEvent.StartRequested)
        onEvent(LinkEvent.PeerDiscovered)
        onEvent(LinkEvent.Connected)
        onEvent(LinkEvent.MtuNegotiated(ATT_MTU))
        val applied = reapply()
        return if (applied) {
            onEvent(LinkEvent.SyncCompleted)
        } else {
            onEvent(LinkEvent.SyncFailed("no handoff payload on this head unit"))
        }
    }

    fun reportLinkLost(): LinkSnapshot = onEvent(LinkEvent.LinkLost)

    fun reconnectNow(): LinkSnapshot = onEvent(LinkEvent.ReconnectTick)

    fun reset() {
        onEvent(LinkEvent.Reset)
    }

    val isLinked: Boolean get() = _state.value.state == LinkState.SYNCED

    companion object {
        /** Requested ATT MTU for the handoff channel (payload 182 bytes). */
        const val ATT_MTU: Int = 185
    }
}
