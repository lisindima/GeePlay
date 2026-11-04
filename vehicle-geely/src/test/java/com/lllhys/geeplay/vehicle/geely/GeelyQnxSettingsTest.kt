package com.lllhys.geeplay.vehicle.geely

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyQnxSettingsTest {
    @Test fun disabledByDefaultAndPersistsIndependentWindows() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("geeplay_qnx_windows", 0).edit().clear().commit()
        assertNull(GeelyQnxSettings.load(app, true))
        assertNull(GeelyQnxSettings.load(app, false))
        val map = GeelyQnxLayout(GeelyQnxWindowConfig("127.0.0.1", 53281, true), 10, 20, 640, 320)
        val turns = GeelyQnxLayout(GeelyQnxWindowConfig("127.0.0.1", 53283, true), 900, 20, 320, 160)
        GeelyQnxSettings.save(app, true, map)
        GeelyQnxSettings.save(app, false, turns)
        assertEquals(map, GeelyQnxSettings.load(app, true))
        assertEquals(turns, GeelyQnxSettings.load(app, false))
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxSettings.save(app, false, map) }
        assertEquals(turns, GeelyQnxSettings.load(app, false))
        val moved = map.copy(endpoint = map.endpoint.copy(host = "192.0.2.1"))
        GeelyQnxSettings.save(app, true, moved)
        assertEquals(moved, GeelyQnxSettings.load(app, true))
        assertEquals(turns, GeelyQnxSettings.load(app, false))
        GeelyQnxSettings.save(app, true, null)
        assertNull(GeelyQnxSettings.load(app, true))
        assertEquals(turns, GeelyQnxSettings.load(app, false))
    }
}
