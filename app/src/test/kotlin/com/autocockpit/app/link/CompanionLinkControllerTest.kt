package com.autocockpit.app.link

import com.autocockpit.core.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionLinkControllerTest {

    @Test
    fun resyncWithPayloadSyncs() {
        val controller = CompanionLinkController()
        val snapshot = controller.resync { true }
        assertEquals(LinkState.SYNCED, snapshot.state)
        assertTrue(controller.isLinked)
        assertEquals(CompanionLinkController.ATT_MTU, snapshot.mtu)
    }

    @Test
    fun resyncWithoutPayloadDegrades() {
        val controller = CompanionLinkController()
        val snapshot = controller.resync { false }
        assertEquals(LinkState.DEGRADED, snapshot.state)
        assertFalse(controller.isLinked)
        assertTrue(snapshot.lastError!!.contains("no handoff payload"))
    }

    @Test
    fun linkLossMovesToDegradedWithAttempt() {
        val controller = CompanionLinkController()
        controller.resync { true }
        val snapshot = controller.reportLinkLost()
        assertEquals(LinkState.DEGRADED, snapshot.state)
        assertEquals(1, snapshot.attempt)
        assertFalse(snapshot.exhausted)
    }

    @Test
    fun reconnectTickReturnsToScanning() {
        val controller = CompanionLinkController()
        controller.resync { true }
        controller.reportLinkLost()
        assertEquals(LinkState.SCANNING, controller.reconnectNow().state)
    }

    @Test
    fun resetClearsAttempts() {
        val controller = CompanionLinkController()
        controller.resync { true }
        controller.reportLinkLost()
        controller.reset()
        assertEquals(LinkState.DISCONNECTED, controller.state.value.state)
        assertEquals(0, controller.state.value.attempt)
    }
}
