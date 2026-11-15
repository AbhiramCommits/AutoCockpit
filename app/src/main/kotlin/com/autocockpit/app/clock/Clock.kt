package com.autocockpit.app.clock

import android.os.SystemClock

/** Injected time source so degradation tiers are deterministic in tests. */
interface Clock {
    fun nowMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

object AndroidClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
