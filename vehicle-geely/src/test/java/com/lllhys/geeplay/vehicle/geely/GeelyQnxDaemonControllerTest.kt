package com.lllhys.geeplay.vehicle.geely

import org.junit.Assert.*
import org.junit.Test

class GeelyQnxDaemonControllerTest {
    @Test fun rejectsAtlasPortsAndCriticalLayersBeforeShellAccess() {
        val controller = GeelyQnxDaemonController { error("Unexpected shell access") }
        assertThrows(IllegalArgumentException::class.java) { controller.start(50210, 1) }
        assertThrows(IllegalArgumentException::class.java) { controller.start(53281, 9) }
    }

    @Test fun refusesToKillRecycledPidBelongingToAtlas() {
        val commands = mutableListOf<String>()
        val controller = GeelyQnxDaemonController { command ->
            commands.add(command)
            if (command.startsWith("pidin")) "123 /tmp/ghudbridgelited-dim --port 50210 --z 1"
            else "GEEPLAY_PID=123\n"
        }
        assertEquals(123, controller.start(53281, 1))
        assertThrows(IllegalStateException::class.java) { controller.stop(53281) }
        assertFalse(commands.any { it.startsWith("kill") })
    }

    @Test fun stopsOnlyConfirmedOwnedProcess() {
        val commands = mutableListOf<String>()
        var running = true
        val controller = GeelyQnxDaemonController { command ->
            commands.add(command)
            if (command == "kill 123") { running = false; "" }
            else if (command.startsWith("pidin")) if (running) "123 /tmp/geeplay-navd-53281 --port 53281 --z 1" else ""
            else "GEEPLAY_PID=123\n"
        }
        controller.start(53281, 1)
        controller.stop(53281)
        controller.stop(53281)
        assertEquals(1, commands.count { it == "kill 123" })
    }
}
