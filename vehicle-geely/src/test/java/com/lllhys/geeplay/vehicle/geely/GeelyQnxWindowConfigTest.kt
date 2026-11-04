package com.lllhys.geeplay.vehicle.geely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GeelyQnxWindowConfigTest {
    @Test fun defaultIsOffAndUsesAnIndependentPort() {
        val config = GeelyQnxWindowConfig(host = "")
        assertFalse(config.enabled)
        assertEquals(53281, config.port)
    }

    @Test fun enabledEndpointNeedsAHostAndCannotUseAtlasDimPorts() {
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxWindowConfig("", enabled = true) }
        for (port in listOf(50210, 50211, 50212, 0, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { GeelyQnxWindowConfig("192.0.2.1", port, true) }
        }
        assertEquals(54000, GeelyQnxWindowConfig("192.0.2.1", 54000, true).port)
    }
}
