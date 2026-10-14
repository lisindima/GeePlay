package com.lllhys.geeplay.vehicle.geely.evcc

import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccValue
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvccVehicleTelemetryStoreTest {
    private var now = 1_000L
    private var wallNow = 1_700_000_000_000L
    private val store = EvccVehicleTelemetryStore(
        elapsedRealtime = { now },
        wallClockMillis = { wallNow },
    )

    @Test
    fun `complete battery state becomes a CarPlay vehicle snapshot`() {
        accept(
            EvccLogicalSignal.BATTERY_SOC,
            1_051_136L,
            EvccValue.ofDouble(50.0),
            recordStatus = EvccApiContract.Status.OK,
        )
        accept(EvccLogicalSignal.RANGE_EV_KM, 1_054_976L, EvccValue.ofDouble(210.4))
        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(1))

        val snapshot = store.vehicleStatus(lowChargePercent = 20)!!

        assertEquals(210, snapshot.rangeKm)
        assertEquals(50.0, snapshot.batteryPercent, 0.0)
        assertEquals(421, snapshot.maxRangeKm)
        assertTrue(snapshot.charging)
        assertFalse(snapshot.rangeWarning)
        assertNull(snapshot.currentChargeWh)
        assertNull(snapshot.maxChargeWh)
    }

    @Test
    fun `range extender keeps total gasoline and electric ranges distinct`() {
        accept(EvccLogicalSignal.BATTERY_SOC, 1_051_136L, EvccValue.ofDouble(51.0))
        accept(EvccLogicalSignal.RANGE_TOTAL_KM, 1_050_624L, EvccValue.ofDouble(173.0))
        accept(EvccLogicalSignal.RANGE_GASOLINE_KM, 1_054_720L, EvccValue.ofDouble(89.0))
        accept(EvccLogicalSignal.RANGE_EV_KM, 1_054_976L, EvccValue.ofDouble(84.0))
        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(0))

        val snapshot = store.vehicleStatus(lowChargePercent = 20)!!
        val powertrains = requireNotNull(snapshot.powertrainRanges)

        assertEquals(173, snapshot.rangeKm)
        assertFalse(snapshot.rangeWarning)
        assertEquals(setOf(VehicleEngineType.GASOLINE, VehicleEngineType.ELECTRIC), powertrains.keys)
        assertEquals(89, powertrains.getValue(VehicleEngineType.GASOLINE).rangeKm)
        assertEquals(84, powertrains.getValue(VehicleEngineType.ELECTRIC).rangeKm)
        assertEquals(false, powertrains.getValue(VehicleEngineType.ELECTRIC).rangeWarning)
        assertEquals(165, powertrains.getValue(VehicleEngineType.ELECTRIC).maxRangeKm)
    }

    @Test
    fun `range extender falls back to summing fuel and electric range when total is unavailable`() {
        accept(EvccLogicalSignal.BATTERY_SOC, 1_051_136L, EvccValue.ofDouble(20.0))
        accept(EvccLogicalSignal.RANGE_GASOLINE_KM, 1_054_720L, EvccValue.ofDouble(89.0))
        accept(EvccLogicalSignal.RANGE_EV_KM, 1_054_976L, EvccValue.ofDouble(84.0))
        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(0))

        val snapshot = store.vehicleStatus(lowChargePercent = 20)!!

        assertEquals(173, snapshot.rangeKm)
        assertFalse(snapshot.rangeWarning)
        assertEquals(true, snapshot.powertrainRanges
            ?.getValue(VehicleEngineType.ELECTRIC)
            ?.rangeWarning)
    }

    @Test
    fun `battery snapshot waits for required values and rejects invalid values`() {
        accept(EvccLogicalSignal.BATTERY_SOC, 1_051_136L, EvccValue.ofDouble(101.0))
        accept(EvccLogicalSignal.RANGE_EV_KM, 1_054_976L, EvccValue.ofDouble(100.0))
        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(0))

        assertNull(store.vehicleStatus(lowChargePercent = 20))

        accept(EvccLogicalSignal.BATTERY_SOC, 1_051_136L, EvccValue.ofDouble(15.0))
        assertTrue(store.vehicleStatus(lowChargePercent = 20)!!.rangeWarning)
    }

    @Test
    fun `charging state is required and accepts only a recognized state`() {
        accept(EvccLogicalSignal.BATTERY_SOC, 1_051_136L, EvccValue.ofDouble(60.0))
        accept(EvccLogicalSignal.RANGE_EV_KM, 1_054_976L, EvccValue.ofDouble(240.0))

        assertNull(store.vehicleStatus(lowChargePercent = 20))

        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(0))
        assertFalse(store.vehicleStatus(lowChargePercent = 20)!!.charging)

        accept(EvccLogicalSignal.CHARGING, 605_225_728L, EvccValue.ofLong(1))
        assertTrue(store.vehicleStatus(lowChargePercent = 20)!!.charging)
    }

    @Test
    fun `speed and Android gear can be combined across validated sources`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(36.0),
            scale = 1.0 / 3.6,
            source = EvccApiContract.Source.ADAPT_API,
        )
        accept(
            EvccLogicalSignal.GEAR,
            289_408_000L,
            EvccValue.ofLong(4),
            source = EvccApiContract.Source.CAPI,
            gearValues = mapOf(4L to VehicleGear.PARK),
        )

        val motion = store.motion()!!

        assertEquals(10.0, motion.speedMetersPerSecond, 0.0001)
        assertEquals(VehicleGear.PARK, motion.gear)
    }

    @Test
    fun `enum metadata can translate numeric gear without guessing its ordinal`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(0.0),
            scale = 1.0 / 3.6,
            source = EvccApiContract.Source.ADAPT_API,
        )
        accept(
            EvccLogicalSignal.GEAR,
            2_097_664L,
            EvccValue.ofLong(4).copy(displayValue = "4"),
            enumOptions = listOf("1=NEUTRAL", "2=REVERSE", "4=PARK", "8=DRIVE"),
            source = EvccApiContract.Source.ADAPT_API,
        )

        assertEquals(VehicleGear.PARK, store.motion()!!.gear)
    }

    @Test
    fun `source generation invalidates observations from the previous source instance`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(20.0),
            scale = 1.0 / 3.6,
            source = EvccApiContract.Source.ADAPT_API,
        )
        accept(
            EvccLogicalSignal.GEAR,
            289_408_000L,
            EvccValue.ofLong(4),
            source = EvccApiContract.Source.CAPI,
            gearValues = mapOf(4L to VehicleGear.PARK),
        )
        assertTrue(store.motion() != null)

        store.sourceState(EvccApiContract.Source.ADAPT_API, sourceGeneration = 2, EvccApiContract.Status.AVAILABLE)

        assertNull(store.motion())
    }

    @Test
    fun `motion never combines speed and gear from different source generations`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(20.0),
            scale = 1.0 / 3.6,
            sourceGeneration = 1,
            source = EvccApiContract.Source.ADAPT_API,
        )
        accept(
            EvccLogicalSignal.GEAR,
            2_097_664L,
            EvccValue.ofLong(8).copy(displayValue = "DRIVE"),
            sourceGeneration = 2,
            source = EvccApiContract.Source.ADAPT_API,
        )

        assertNull(store.motion())
    }

    @Test
    fun `motion expires without fresh EVCC observations`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(20.0),
            scale = 1.0 / 3.6,
            source = EvccApiContract.Source.ADAPT_API,
        )
        accept(
            EvccLogicalSignal.GEAR,
            289_408_000L,
            EvccValue.ofLong(4),
            source = EvccApiContract.Source.CAPI,
            gearValues = mapOf(4L to VehicleGear.PARK),
        )
        now += 5_001

        assertNull(store.motion())
    }

    @Test
    fun `park state fails closed when gear is missing or stale`() {
        assertNull(store.parked(2_000))
        accept(
            EvccLogicalSignal.GEAR,
            289_408_000L,
            EvccValue.ofLong(4),
            source = EvccApiContract.Source.CAPI,
            gearValues = mapOf(4L to VehicleGear.PARK, 8L to VehicleGear.DRIVE),
        )
        assertEquals(true, store.parked(2_000))
        now += 2_001
        assertNull(store.parked(2_000))
    }

    @Test
    fun `tyre telemetry applies profile scale and offset and expires`() {
        accept(
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
            1L,
            EvccValue.ofDouble(2.5),
            scale = 100.0,
        )
        accept(
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT,
            2L,
            EvccValue.ofDouble(68.0),
            scale = 5.0 / 9.0,
            offset = -32.0 * 5.0 / 9.0,
        )

        val reading = store.tyreTelemetry(10_000)!!.wheels
            .getValue(com.shilapi.xcertplay.vehicle.VehicleWheelPosition.FRONT_LEFT)
        assertEquals(250.0, reading.pressureKpa!!, 0.001)
        assertEquals(20.0, reading.temperatureC!!, 0.001)

        now += 10_001
        assertNull(store.tyreTelemetry(10_000))
    }

    @Test
    fun `motion diagnostics expose origin converted value and cache age`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(36.0),
            scale = 1.0 / 3.6,
            source = EvccApiContract.Source.ADAPT_API,
            origin = EvccObservationOrigin.PROPERTY_EVENT,
        )
        accept(
            EvccLogicalSignal.GEAR,
            289_408_000L,
            EvccValue.ofLong(8),
            source = EvccApiContract.Source.CAPI,
            gearValues = mapOf(8L to VehicleGear.DRIVE),
            origin = EvccObservationOrigin.REFRESH_READ,
        )
        now += 250

        val diagnostics = store.motionDiagnostics()

        assertTrue("raw=36.000" in diagnostics)
        assertTrue("mps=10.000" in diagnostics)
        assertTrue("origin=PROPERTY_EVENT" in diagnostics)
        assertTrue("raw=8" in diagnostics)
        assertTrue("gear=D" in diagnostics)
        assertTrue("origin=REFRESH_READ" in diagnostics)
        assertEquals(2, "ageMs=250".toRegex().findAll(diagnostics).count())
    }

    @Test
    fun `property diagnostics retain source identity raw normalized origin and age`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(36.0, observedAt = wallNow - 80),
            scale = 1.0 / 3.6,
            sourceGeneration = 7,
            source = EvccApiContract.Source.ADAPT_API,
            origin = EvccObservationOrigin.REFRESH_READ,
        )
        now += 125

        val diagnostic = store.propertyDiagnostic(EvccLogicalSignal.VEHICLE_SPEED)

        assertTrue("signal=VEHICLE_SPEED" in diagnostic)
        assertTrue("source=ADAPT_API" in diagnostic)
        assertTrue("id=1048832" in diagnostic)
        assertTrue("generation=7" in diagnostic)
        assertTrue("raw=36.000" in diagnostic)
        assertTrue("normalized=10.000" in diagnostic)
        assertTrue("unit=m/s" in diagnostic)
        assertTrue("origin=REFRESH_READ" in diagnostic)
        assertTrue("ageMs=125" in diagnostic)
        assertTrue("sourceObservedAt=${wallNow - 80}" in diagnostic)
        assertTrue("sourceClock=wall-ms" in diagnostic)
        assertTrue("sourceAgeMs=80" in diagnostic)
    }

    @Test
    fun `property diagnostics recognize EVCC monotonic nanosecond observations`() {
        accept(
            EvccLogicalSignal.VEHICLE_SPEED,
            1_048_832L,
            EvccValue.ofDouble(4.0, observedAt = (now - 125L) * 1_000_000L),
            source = EvccApiContract.Source.ADAPT_API,
        )

        val diagnostic = store.propertyDiagnostic(EvccLogicalSignal.VEHICLE_SPEED)

        assertTrue("sourceClock=elapsed-ns" in diagnostic)
        assertTrue("sourceAgeMs=125" in diagnostic)
    }

    @Test
    fun `speed source diagnostics expose configured sample and refresh cadence`() {
        val source = EvccVehicleSpeedSource(
            telemetry = store,
            elapsedRealtime = { now },
            sampleMillis = 100L,
        )

        val diagnostics = source.diagnostics()

        assertTrue("sampleIntervalMs=100" in diagnostics)
        assertTrue("refreshEverySamples=10" in diagnostics)
    }

    private fun accept(
        signal: EvccLogicalSignal,
        id: Long,
        value: EvccValue,
        scale: Double = 1.0,
        offset: Double = 0.0,
        enumOptions: List<String> = emptyList(),
        sourceGeneration: Long = 1,
        source: String = EvccApiContract.Source.ADAPT_API,
        gearValues: Map<Long, VehicleGear> = emptyMap(),
        recordStatus: String = EvccApiContract.Status.AVAILABLE,
        origin: EvccObservationOrigin = EvccObservationOrigin.UNSPECIFIED,
    ) {
        val descriptor = EvccPropertyDescriptor(
            source = source,
            canonicalId = id,
            name = signal.name,
            areaId = 0,
            dataType = value.type,
            access = "R",
            enumOptions = enumOptions,
            sourceGeneration = sourceGeneration,
        )
        val candidate = EvccPropertyCandidate(
            id,
            source = source,
            scale = scale,
            offset = offset,
            gearValues = gearValues,
        )
        val selector = EvccPropertySelector(
            id = id,
            source = source,
            areaId = 0,
            resolutionMode = EvccApiContract.ResolutionMode.STRICT_UNIQUE,
        )
        val binding = EvccResolvedProperty(signal, candidate, selector, descriptor)
        assertTrue(
            store.accept(
                binding,
                EvccPropertyRecord(
                    requestedSelector = selector,
                    resolvedSource = descriptor.source,
                    descriptor = descriptor,
                    value = value,
                    status = recordStatus,
                ),
                origin,
            ),
        )
    }
}
