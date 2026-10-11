package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import android.content.pm.PackageInfo
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccApiInfo
import com.kooo.evcc.evccapi.EvccCapability
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSessionInfo
import com.kooo.evcc.evccapi.EvccSourceState
import com.kooo.evcc.evccapi.EvccValue
import com.kooo.evcc.evccapi.client.EvccApiClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EvccVehicleDataAdapterTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun resetSettings() {
        EvccVehicleDataSettings.saveVehicleStatusEnabled(context, false)
        EvccVehicleDataSettings.saveVehicleSpeedEnabled(context, false)
    }

    @Test
    fun `active session keeps its frozen demand until the session closes`() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = EvccApiContract.EVCC_PACKAGE },
        )
        EvccVehicleDataSettings.saveVehicleStatusEnabled(context, true)
        lateinit var fake: FakeEvccClientPort
        val adapter = EvccVehicleDataAdapter(context) { _, listener ->
            FakeEvccClientPort(listener).also { fake = it }
        }
        try {
            adapter.prepare()
            assertEquals(1, fake.connectCount)

            fake.openReadySession()
            waitUntil { "state=READY" in adapter.diagnostics() }
            val session = adapter.createSession()
            assertNotNull(session.statusProvider)

            EvccVehicleDataSettings.saveVehicleStatusEnabled(context, false)
            adapter.prepare()
            assertEquals(0, fake.disconnectCount)
            assertTrue("BATTERY_SOC" in adapter.diagnostics())

            session.close()
            assertEquals(1, fake.disconnectCount)
        } finally {
            adapter.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for EVCC adapter" }
            Thread.sleep(10)
        }
    }

    private class FakeEvccClientPort(
        private val listener: EvccApiClient.Listener,
    ) : EvccClientPort {
        var connectCount = 0
        var disconnectCount = 0
        private var state = EvccApiClient.ConnectionState.DISCONNECTED

        override fun connect() {
            connectCount++
        }

        override fun disconnect() {
            disconnectCount++
            state = EvccApiClient.ConnectionState.DISCONNECTED
        }

        override fun connectionState(): EvccApiClient.ConnectionState = state

        fun openReadySession() {
            state = EvccApiClient.ConnectionState.CONNECTED
            listener.onConnectionStateChanged(state, "connected")
            listener.onSessionReady(
                EvccApiInfo(serviceVersionName = "3.2.17"),
                EvccSessionInfo(grantedPermissions = REQUIRED_PERMISSIONS.toList()),
            )
        }

        override fun queryCapabilities(): List<EvccCapability> =
            REQUIRED_PERMISSIONS.map { EvccCapability(id = it, available = true) }

        override fun querySourceStates(): List<EvccSourceState> = listOf(
            EvccSourceState(
                source = EvccApiContract.Source.ADAPT_API,
                state = EvccApiContract.Status.AVAILABLE,
                sourceGeneration = 1,
            ),
            EvccSourceState(
                source = EvccApiContract.Source.CAPI,
                state = EvccApiContract.Status.AVAILABLE,
                sourceGeneration = 1,
            ),
        )

        override fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord =
            record(selector, value(requireNotNull(selector.id)))

        override fun readProperty(
            selector: EvccPropertySelector,
            forceRefresh: Boolean,
        ): EvccPropertyRecord = record(selector, value(requireNotNull(selector.id)))

        override fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult =
            EvccOperationResult(status = EvccApiContract.Status.OK, acceptedCount = selectors.size)

        override fun close() = Unit

        private fun record(selector: EvccPropertySelector, value: EvccValue): EvccPropertyRecord {
            val id = requireNotNull(selector.id)
            val areaId = requireNotNull(selector.areaId)
            val descriptor = EvccPropertyDescriptor(
                source = selector.source,
                canonicalId = id,
                name = id.toString(),
                areaId = areaId,
                dataType = value.type,
                access = "R",
                sourceGeneration = 1,
            )
            return EvccPropertyRecord(
                requestedSelector = selector,
                resolvedSource = selector.source,
                descriptor = descriptor,
                value = value,
                status = EvccApiContract.Status.AVAILABLE,
            )
        }

        private fun value(id: Long): EvccValue = when (id) {
            1_051_136L -> EvccValue.ofDouble(50.0)
            1_054_976L -> EvccValue.ofDouble(200.0)
            605_225_728L -> EvccValue.ofLong(0)
            else -> error("Unexpected property $id")
        }

        private companion object {
            val REQUIRED_PERMISSIONS = setOf(
                EvccApiContract.Permission.PROPERTY_DIRECTORY,
                EvccApiContract.Permission.PROPERTY_READ,
                EvccApiContract.Permission.PROPERTY_LISTEN,
            )
        }
    }
}
