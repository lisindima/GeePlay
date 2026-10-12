package com.lllhys.geeplay.vehicle.geely.evcc

import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvccDynamicPropertyDiscoveryTest {
    @Test
    fun `discovers four-wheel pressure and converts bar to kPa`() {
        val descriptors = listOf(
            descriptor(1, 1, "TPMS_FRONT_LEFT_PRESSURE_BAR"),
            descriptor(2, 2, "TPMS_FRONT_RIGHT_PRESSURE_BAR"),
            descriptor(3, 4, "TPMS_REAR_LEFT_PRESSURE_BAR"),
            descriptor(4, 8, "TPMS_REAR_RIGHT_PRESSURE_BAR"),
        )

        val resolved = EvccDynamicPropertyDiscovery.resolve(descriptors)

        assertEquals(4, resolved.size)
        assertTrue(resolved.all { it.candidate.scale == 100.0 })
        assertEquals(
            setOf(
                EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
                EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT,
                EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT,
                EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT,
            ),
            resolved.mapTo(mutableSetOf(), EvccResolvedProperty::signal),
        )
    }

    @Test
    fun `ambiguous tyre property and unrelated map property stay unavailable`() {
        val resolved = EvccDynamicPropertyDiscovery.resolve(
            listOf(
                descriptor(1, 0, "TPMS_PRESSURE"),
                descriptor(2, 0, "CENTER_SCREEN_MAP_VISIBLE"),
            ),
        )

        assertTrue(resolved.isEmpty())
    }

    private fun descriptor(id: Long, area: Int, name: String) = EvccPropertyDescriptor(
        source = EvccApiContract.Source.ADAPT_API,
        canonicalId = id,
        name = name,
        areaId = area,
        areaName = name,
        dataType = "FLOAT",
        access = "R",
    )
}
