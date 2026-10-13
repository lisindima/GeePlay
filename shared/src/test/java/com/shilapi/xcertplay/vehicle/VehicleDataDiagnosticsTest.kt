package com.shilapi.xcertplay.vehicle

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VehicleDataDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @After
    fun reset() {
        VehicleDataDiagnostics.resetForTests()
    }

    @Test
    fun stableTraceIsSanitizedAndPersistedForLaterReports() {
        val files = temporary.newFolder("files")
        VehicleDataDiagnostics.resetForTests(
            filesDir = files,
            wallClockMillis = { 1_700_000_000_123L },
            elapsedRealtimeMillis = { 45_678L },
        )

        val line = VehicleDataDiagnostics.record(
            stage = "evcc-read",
            detail = "signal=VEHICLE_SPEED raw=36.000\nnormalized=10.000 token=do-not-store",
        )

        assertTrue(line.startsWith("VEHICLE-DATA seq=1 wallMs=1700000000123 elapsedMs=45678 stage=evcc-read "))
        assertTrue("raw=36.000 normalized=10.000" in line)
        assertFalse('\n' in line)
        assertTrue("token=<redacted>" in line)
        assertFalse("do-not-store" in line)
        assertTrue(VehicleDataDiagnostics.awaitIdle(2_000))
        val persisted = files.resolve("logs/${VehicleDataDiagnostics.CURRENT_LOG_NAME}").readText()
        assertTrue(line in persisted)
        assertTrue(line in VehicleDataDiagnostics.report())
    }

    @Test
    fun recentTraceDropsOnlyTheOldestEntryAtItsBound() {
        VehicleDataDiagnostics.resetForTests(
            wallClockMillis = { 1L },
            elapsedRealtimeMillis = { 2L },
        )

        repeat(513) { VehicleDataDiagnostics.record("test", "sample=value-$it") }

        val entries = VehicleDataDiagnostics.report().lineSequence()
            .filter { it.startsWith("VEHICLE-DATA ") }
            .toList()
        assertTrue(entries.size == 512)
        assertTrue(entries.first().contains("seq=2 "))
        assertTrue(entries.last().contains("seq=513 "))
    }
}
