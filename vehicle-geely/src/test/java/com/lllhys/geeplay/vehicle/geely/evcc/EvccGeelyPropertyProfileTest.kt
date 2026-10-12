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
        assertEquals(
            listOf(1.0, 1.0),
            bindings.getValue(EvccLogicalSignal.VEHICLE_SPEED).candidates.map(EvccPropertyCandidate::scale),
        )
        val gear = bindings.getValue(EvccLogicalSignal.GEAR).candidates
        assertEquals(
            listOf(
                EvccApiContract.Source.CAPI to 0x21401A01L,
                EvccApiContract.Source.ADAPT_API to 0x200200L,
            ),
            gear.map { it.source to it.id },
        )
        assertEquals(VehicleGear.PARK, gear[0].gearValues[0L])
        assertEquals(VehicleGear.NEUTRAL, gear[0].gearValues[1L])
        assertEquals(VehicleGear.REVERSE, gear[0].gearValues[2L])
        assertEquals(VehicleGear.DRIVE, gear[0].gearValues[3L])
        assertEquals(VehicleGear.PARK, gear[1].gearValues[0x200230L])
        assertEquals(VehicleGear.NEUTRAL, gear[1].gearValues[0x200240L])
        assertEquals(VehicleGear.REVERSE, gear[1].gearValues[0x200210L])
        assertEquals(VehicleGear.DRIVE, gear[1].gearValues[0x200220L])
    }

    private fun candidates(binding: EvccPropertyBinding): List<Pair<String, Long>> =
        binding.candidates.map { it.source to it.id }
}
