package com.autocockpit.app

import com.autocockpit.app.clock.Clock

class TestClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
    override fun elapsedRealtimeMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
