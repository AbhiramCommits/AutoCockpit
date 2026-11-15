package com.autocockpit.app.ui

/** Stable test tags shared by the Robolectric and Espresso suites. */
object UiTags {
    const val NAV_MEDIA = "nav_media"
    const val NAV_GAUGE = "nav_gauge"
    const val NAV_SETTINGS = "nav_settings"

    const val MEDIA_SCREEN = "media_screen"
    const val MEDIA_BACK = "media_back"
    const val MEDIA_NOW_PLAYING = "media_now_playing"
    const val MEDIA_PLAY_TOGGLE = "media_play_toggle"
    const val MEDIA_SKIP_NEXT = "media_skip_next"
    const val MEDIA_SKIP_PREVIOUS = "media_skip_previous"

    const val GAUGE_SCREEN = "gauge_screen"
    const val GAUGE_SURFACE = "gauge_surface"
    const val GAUGE_TIER_LABEL = "gauge_tier_label"
    const val GAUGE_BENCHMARK_TOGGLE = "gauge_benchmark_toggle"

    const val SETTINGS_SCREEN = "settings_screen"
    const val SETTINGS_LINK_STATE = "settings_link_state"
    const val SETTINGS_LINK_ATTEMPT = "settings_link_attempt"
    const val SETTINGS_RESYNC = "settings_resync"
    const val SETTINGS_RECONNECT = "settings_reconnect"
    const val SETTINGS_GATEWAY_BANNER = "settings_gateway_banner"
    const val SETTINGS_HVAC_SLIDER = "settings_hvac_slider"
    const val SETTINGS_HVAC_APPLY = "settings_hvac_apply"
    const val SETTINGS_HVAC_VALUE = "settings_hvac_value"

    fun signalRow(signalName: String): String = "signal_row_$signalName"
    fun mediaNode(id: String): String = "media_node_$id"
}
