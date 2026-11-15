package com.autocockpit.core.handoff

import com.autocockpit.core.domain.HandoffPayload
import java.util.concurrent.CopyOnWriteArrayList

/** Result of committing a handoff into the repository. */
data class HandoffCommit(
    val accepted: Boolean,
    val revision: Int,
    val trackCount: Int,
    val driverName: String,
    val reason: String? = null,
)

/**
 * In-memory store for the last committed phone handoff. The media browser reads the handoff
 * queue from here and the settings screen reads the driver profile.
 *
 * Listener callbacks are invoked on the thread that commits; the UI layer hops to the main thread.
 */
class HandoffRepository {
    private val lock = Any()
    private var payload: HandoffPayload? = null
    private var revision: Int = 0
    private val listeners = CopyOnWriteArrayList<(HandoffPayload?) -> Unit>()

    fun commit(payload: HandoffPayload): HandoffCommit = synchronized(lock) {
        this.payload = payload
        revision += 1
        notifyListeners(payload)
        HandoffCommit(
            accepted = true,
            revision = revision,
            trackCount = payload.queue.tracks.size,
            driverName = payload.profile.displayName,
        )
    }

    fun current(): HandoffPayload? = synchronized(lock) { payload }

    fun revision(): Int = synchronized(lock) { revision }

    /** Re-applies the current payload (used by the "Re-sync" button). */
    fun reapplyLast(): Boolean = synchronized(lock) {
        val current = payload ?: return false
        revision += 1
        notifyListeners(current)
        true
    }

    fun observe(listener: (HandoffPayload?) -> Unit): AutoCloseable {
        listeners += listener
        listener(current())
        return AutoCloseable { listeners -= listener }
    }

    fun clear() = synchronized(lock) {
        payload = null
        revision += 1
        notifyListeners(null)
    }

    private fun notifyListeners(value: HandoffPayload?) {
        listeners.forEach { runCatching { it(value) } }
    }
}
