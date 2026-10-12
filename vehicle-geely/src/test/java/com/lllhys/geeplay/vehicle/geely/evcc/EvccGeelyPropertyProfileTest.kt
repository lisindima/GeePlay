package com.lllhys.geeplay.vehicle.geely.evcc

import com.kooo.evcc.evccapi.EvccApiContract
import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Test

class EvccGeelyPropertyProfileTest {
    @Test
    fun `profile uses the source and ids validated on G636`() {
        val bindings = EvccGeelyPropertyProfile.bindings.associateBy(EvccPropertyBinding::signal)

        assertEquals(
            listOf(EvccApiContract.Source.ADAPT_API to 1_051_136L),
            candidates(bindings.getValue(EvccLogicalSignal.BATTERY_SOC)),
        )
        assertEquals(
            listOf(EvccApiContract.Source.ADAPT_API to 1_050_624L),
            candidates(bindings.getValue(EvccLogicalSignal.RANGE_TOTAL_KM)),
        )
        assertEquals(
            listOf(EvccApiContract.Source.ADAPT_API to 1_054_720L),
            candidates(bindings.getValue(EvccLogicalSignal.RANGE_GASOLINE_KM)),
        )
        assertEquals(
            listOf(EvccApiContract.Source.ADAPT_API to 1_054_976L),
            candidates(bindings.getValue(EvccLogicalSignal.RANGE_EV_KM)),
        )
        assertEquals(
            listOf(EvccApiContract.Source.ADAPT_API to 605_225_728L),
            candidates(bindings.getValue(EvccLogicalSignal.CHARGING)),
        )
        assertEquals(
            listOf(
                EvccApiContract.Source.ADAPT_API to 1_048_832L,
                EvccApiContract.Source.ADAPT_API to 1_055_232L,
            ),
            candidates(bindings.getValue(EvccLogicalSignal.VEHICLE_SPEED)),
        )
        val gear = bindings.getValue(EvccLogicalSignal.GEAR).candidates.single()
        assertEquals(EvccApiContract.Source.CAPI, gear.source)
        assertEquals(289_408_000L, gear.id)
        assertEquals(VehicleGear.NEUTRAL, gear.gearValues[1L])
        assertEquals(VehicleGear.REVERSE, gear.gearValues[2L])
        assertEquals(VehicleGear.PARK, gear.gearValues[4L])
        assertEquals(VehicleGear.DRIVE, gear.gearValues[8L])
        assertEquals(VehicleGear.DRIVE, gear.gearValues[16_384L])
    }

    private fun candidates(binding: EvccPropertyBinding): List<Pair<String, Long>> =
        binding.candidates.map { it.source to it.id }
}
