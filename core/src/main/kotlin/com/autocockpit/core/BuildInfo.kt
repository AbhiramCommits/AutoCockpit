package com.autocockpit.core

/**
 * Version metadata for the head-unit build. Kept in :core so the JVM-only test suite can
 * assert it without an Android runtime.
 */
object BuildInfo {
    const val VERSION: String = "0.7.0"

    /** Wire schema version understood by the handoff codec and the GATT service. */
    const val SCHEMA_VERSION: Int = 1

    const val APP_NAME: String = "AutoCockpit"

    /** Default ATT payload size (MTU 23 minus the 3-byte ATT header). */
    const val DEFAULT_ATT_PAYLOAD_BYTES: Int = 20
}
