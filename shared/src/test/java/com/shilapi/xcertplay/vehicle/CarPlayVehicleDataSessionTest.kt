package com.shilapi.xcertplay.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayVehicleDataSessionTest {
    @Test
    fun `closing a vehicle data session releases its owner once`() {
        var releases = 0
        val session = CarPlayVehicleDataSession(release = { releases++ })

        session.close()
        session.close()

        assertEquals(1, releases)
    }
}
