package com.lllhys.geeplay.vehicle.geely

import android.view.Display
import com.shilapi.xcertplay.vehicle.VehicleDisplayCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeelyClusterDisplayPolicyTest {
    private val profile = GeelyClusterDisplayProfile(
        id = "test-profile",
        fingerprint = Regex("GEELY/test/1"),
        displayName = "Geely cluster projection",
        width = 1920,
        height = 720,
    )

    @Test
    fun `exact validated profile selects one display`() {
        val selected = GeelyClusterDisplayPolicy("GEELY/test/1", listOf(profile)).select(
            listOf(VehicleDisplayCandidate(11, profile.displayName, 1920, 720, Display.FLAG_PRESENTATION)),
        )

        assertEquals(11, selected?.displayId)
        assertEquals("test-profile", selected?.profileId)
    }

    @Test
    fun `name size fingerprint and uniqueness are all required`() {
        val candidate = VehicleDisplayCandidate(11, profile.displayName, 1920, 720, Display.FLAG_PRESENTATION)
        assertNull(GeelyClusterDisplayPolicy("other", listOf(profile)).select(listOf(candidate)))
        assertNull(GeelyClusterDisplayPolicy("GEELY/test/1", listOf(profile)).select(
            listOf(candidate.copy(width = 1280)),
        ))
        assertNull(GeelyClusterDisplayPolicy("GEELY/test/1", listOf(profile)).select(
            listOf(candidate, candidate.copy(displayId = 12)),
        ))
    }

    @Test
    fun `production policy stays disabled without validated evidence`() {
        assertNull(GeelyClusterDisplayPolicy("GEELY/test/1").select(
            listOf(VehicleDisplayCandidate(11, profile.displayName, 1920, 720, Display.FLAG_PRESENTATION)),
        ))
    }

    @Test
    fun `validated profile still requires explicit user enablement`() {
        val candidate = VehicleDisplayCandidate(11, profile.displayName, 1920, 720, Display.FLAG_PRESENTATION)

        assertNull(
            GeelyClusterDisplayPolicy("GEELY/test/1", listOf(profile), enabled = false)
                .select(listOf(candidate)),
        )
    }
}
