package com.autocockpit.app

import android.app.Application

class AutoCockpitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Build the graph once so the gateway selection banner is decided before the UI appears.
        AppGraph.get(this)
    }
}
