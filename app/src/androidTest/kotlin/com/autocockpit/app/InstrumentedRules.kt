package com.autocockpit.app

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule

/** Shared launch configuration for the instrumented UI suite. */
object InstrumentedRules {

    /** Skips the runtime BLE permission dialog; the handoff is exercised through the loopback stack. */
    fun launchIntent(): Intent = Intent(
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java,
    ).putExtra(MainActivity.EXTRA_SKIP_COMPANION_LINK, true)

    fun activity(rule: ActivityScenarioRule<MainActivity>): MainActivity {
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        return checkNotNull(activity) { "activity not resumed" }
    }
}
