package com.autocockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildInfoTest {
    @Test
    fun versionConstantIsSemantic() {
        assertEquals("0.7.0", BuildInfo.VERSION)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildInfo.VERSION))
    }

    @Test
    fun schemaVersionIsPositive() {
        assertTrue(BuildInfo.SCHEMA_VERSION >= 1)
    }

    @Test
    fun defaultAttPayloadMatchesBleMinimumMtu() {
        assertEquals(20, BuildInfo.DEFAULT_ATT_PAYLOAD_BYTES)
    }
}
