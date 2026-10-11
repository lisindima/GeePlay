package com.lllhys.geeplay.vehicle.geely.evcc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvccDemandRegistryTest {
    @Test
    fun `owners replace and release only their own demand`() {
        val registry = EvccDemandRegistry()
        val statusOwner = Any()
        val motionOwner = Any()

        registry.replace(statusOwner, setOf(EvccLogicalSignal.BATTERY_SOC, EvccLogicalSignal.RANGE_EV_KM))
        registry.replace(motionOwner, setOf(EvccLogicalSignal.VEHICLE_SPEED, EvccLogicalSignal.GEAR))

        assertEquals(
            setOf(
                EvccLogicalSignal.BATTERY_SOC,
                EvccLogicalSignal.RANGE_EV_KM,
                EvccLogicalSignal.VEHICLE_SPEED,
                EvccLogicalSignal.GEAR,
            ),
            registry.current(),
        )

        registry.replace(statusOwner, setOf(EvccLogicalSignal.BATTERY_SOC))
        assertEquals(
            setOf(EvccLogicalSignal.BATTERY_SOC, EvccLogicalSignal.VEHICLE_SPEED, EvccLogicalSignal.GEAR),
            registry.current(),
        )

        registry.release(motionOwner)
        assertEquals(setOf(EvccLogicalSignal.BATTERY_SOC), registry.current())
        registry.release(statusOwner)
        assertTrue(registry.current().isEmpty())
    }

    @Test
    fun `equal demands from different owners remain until the last owner releases`() {
        val registry = EvccDemandRegistry()
        val first = Any()
        val second = Any()
        val demand = setOf(EvccLogicalSignal.VEHICLE_SPEED)

        registry.replace(first, demand)
        registry.replace(second, demand)
        registry.release(first)

        assertEquals(demand, registry.current())
    }
}
