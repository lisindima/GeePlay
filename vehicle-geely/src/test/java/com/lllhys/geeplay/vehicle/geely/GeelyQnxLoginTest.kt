package com.lllhys.geeplay.vehicle.geely

import org.junit.Assert.*
import org.junit.Test

class GeelyQnxLoginTest {
    @Test fun blankInputUsesTheBuiltInLoginAndCustomInputOverridesIt() {
        assertArrayEquals("factory-default".toCharArray(),GeelyQnxLogin.password("","factory-default"))
        assertArrayEquals("custom".toCharArray(),GeelyQnxLogin.password("custom","factory-default"))
        assertTrue(GeelyQnxLogin.password("","").isEmpty())
    }
    @Test fun displaySelectionMatchesAtlasDimModelPolicy() {
        assertEquals(2,GeelyQnxLogin.display("G426_J1"))
        assertEquals(1,GeelyQnxLogin.display("G636"))
    }
}
