package com.lllhys.geeplay.vehicle.geely

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

internal class RecordingBluetoothProfile(
    connected: List<BluetoothDevice>,
    private val directConnectAccepted: Boolean = true,
) : BluetoothProfile {
    private val devices = connected.toMutableList()
    val disconnects = mutableListOf<String>()
    val connects = mutableListOf<String>()

    override fun getConnectedDevices(): List<BluetoothDevice> = devices.toList()

    override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = devices.toList()

    override fun getConnectionState(device: BluetoothDevice): Int =
        if (device in devices) BluetoothProfile.STATE_CONNECTED else BluetoothProfile.STATE_DISCONNECTED

    fun disconnect(device: BluetoothDevice): Boolean {
        disconnects += device.address
        devices -= device
        return true
    }

    fun connect(device: BluetoothDevice): Boolean {
        connects += device.address
        if (directConnectAccepted) devices += device
        return directConnectAccepted
    }

    fun connectFromOem(device: BluetoothDevice) {
        devices += device
    }
}

@RunWith(RobolectricTestRunner::class)
class GeelyBluetoothAudioConflictPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun clearJournal() = GeelyBluetoothMediaJournal(context).replace(emptySet())

    @Test
    @Config(sdk = [30])
    fun `android controller arbitrates the head unit A2DP sink and leaves source profile alone`() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val phone = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val source = RecordingBluetoothProfile(emptyList())
        val sink = RecordingBluetoothProfile(listOf(phone))
        Shadows.shadowOf(adapter).setProfileProxy(BluetoothProfile.A2DP, source)
        Shadows.shadowOf(adapter).setProfileProxy(11, sink)
        val controller = AndroidGeelyA2dpController(context)

        assertEquals(
            GeelyA2dpDisconnectResult.Disconnected(phone.address),
            controller.disconnectSingleConnected(),
        )
        assertEquals(emptyList<String>(), source.disconnects)
        assertEquals(listOf(phone.address), sink.disconnects)

        // Android returns a fresh profile proxy after closeProfileProxy(); mirror that here.
        Shadows.shadowOf(adapter).setProfileProxy(11, sink)
        assertEquals(
            GeelyA2dpRestoreResult(setOf(phone.address), emptySet(), "A2DP restored"),
            controller.restore(setOf(phone.address)),
        )
        assertEquals(emptyList<String>(), source.connects)
        assertEquals(listOf(phone.address), sink.connects)
    }

    @Test
    @Config(sdk = [30])
    fun `OEM Bluetooth music source restores A2DP sink when direct connect is privileged`() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val phone = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val sink = RecordingBluetoothProfile(emptyList(), directConnectAccepted = false)
        Shadows.shadowOf(adapter).setProfileProxy(11, sink)
        var requestedAddress: String? = null
        val controller = AndroidGeelyA2dpController(
            context,
            oemRestoreRequester = GeelyA2dpRestoreRequester { address ->
                requestedAddress = address
                sink.connectFromOem(phone)
                true
            },
        )

        assertEquals(
            GeelyA2dpRestoreResult(
                setOf(phone.address),
                emptySet(),
                "A2DP restored through OEM Bluetooth music",
            ),
            controller.restore(setOf(phone.address)),
        )
        assertEquals(phone.address, requestedAddress)
        assertTrue(sink.connects.isEmpty())
    }

    @Test
    fun `OEM restore request explicitly targets trusted Flyme Bluetooth music source`() {
        var captured: android.content.Intent? = null
        val requester = FlymeBluetoothMusicRestoreRequester(
            context,
            deviceSupported = { true },
            receiverAvailable = { component ->
                assertEquals(FlymeBluetoothMusicRestoreRequester.OEM_PACKAGE, component.packageName)
                assertEquals(FlymeBluetoothMusicRestoreRequester.OEM_RECEIVER, component.className)
                true
            },
            sendBroadcast = { intent -> captured = intent },
        )

        assertTrue(requester.request("AA:BB:CC:DD:EE:FF"))

        val intent = requireNotNull(captured)
        assertEquals(
            FlymeBluetoothMusicRestoreRequester.ACTION_CHANGE_BLUETOOTH_SOURCE,
            intent.action,
        )
        assertEquals(FlymeBluetoothMusicRestoreRequester.OEM_PACKAGE, intent.component?.packageName)
        assertEquals(FlymeBluetoothMusicRestoreRequester.OEM_RECEIVER, intent.component?.className)
        assertEquals(context.packageName, intent.getStringExtra(FlymeBluetoothMusicRestoreRequester.EXTRA_FROM))
        assertTrue(intent.getBooleanExtra(FlymeBluetoothMusicRestoreRequester.EXTRA_NEED_PLAY, false))
    }

    @Test
    fun `OEM restore request rejects unavailable receiver and invalid address`() {
        var broadcasts = 0
        val requester = FlymeBluetoothMusicRestoreRequester(
            context,
            deviceSupported = { true },
            receiverAvailable = { false },
            sendBroadcast = { broadcasts += 1 },
        )

        assertFalse(requester.request("AA:BB:CC:DD:EE:FF"))
        assertFalse(requester.request("not-a-bluetooth-address"))
        assertEquals(0, broadcasts)
    }

    @Test
    fun `acquire disconnects one A2DP device and release restores only our device`() {
        val disconnected = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val controller = object : GeelyA2dpController {
            var disconnects = 0
            var restores = emptySet<String>()
            override fun disconnectSingleConnected(): GeelyA2dpDisconnectResult {
                disconnects += 1
                return if (disconnects == 1) {
                    disconnected.countDown()
                    GeelyA2dpDisconnectResult.Disconnected("AA:BB:CC:DD:EE:FF")
                } else {
                    GeelyA2dpDisconnectResult.NothingConnected
                }
            }

            override fun restore(addresses: Set<String>): GeelyA2dpRestoreResult {
                restores = addresses
                restored.countDown()
                return GeelyA2dpRestoreResult(addresses, emptySet(), "restored")
            }
        }
        val worker = Executors.newSingleThreadScheduledExecutor()
        val policy = GeelyBluetoothAudioConflictPolicy(
            controller,
            GeelyBluetoothMediaJournal(context),
            worker,
            onStatus = {},
            pollMillis = 60_000,
        )
        val owner = Any()
        try {
            policy.acquire(owner)
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (GeelyBluetoothMediaJournal(context).pending().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(setOf("AA:BB:CC:DD:EE:FF"), GeelyBluetoothMediaJournal(context).pending())

            policy.release(owner)
            assertTrue(restored.await(2, TimeUnit.SECONDS))
            assertEquals(setOf("AA:BB:CC:DD:EE:FF"), controller.restores)
            val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (GeelyBluetoothMediaJournal(context).pending().isNotEmpty() &&
                System.nanoTime() < restoreDeadline
            ) {
                Thread.sleep(5)
            }
            assertTrue(GeelyBluetoothMediaJournal(context).pending().isEmpty())
        } finally {
            policy.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `stale owner release cannot restore a replacement session`() {
        val restored = CountDownLatch(1)
        val controller = object : GeelyA2dpController {
            override fun disconnectSingleConnected() = GeelyA2dpDisconnectResult.NothingConnected
            override fun restore(addresses: Set<String>): GeelyA2dpRestoreResult {
                restored.countDown()
                return GeelyA2dpRestoreResult(addresses, emptySet(), "restored")
            }
        }
        GeelyBluetoothMediaJournal(context).add("AA:BB:CC:DD:EE:FF")
        val worker = Executors.newSingleThreadScheduledExecutor()
        val policy = GeelyBluetoothAudioConflictPolicy(
            controller,
            GeelyBluetoothMediaJournal(context),
            worker,
            onStatus = {},
            pollMillis = 60_000,
        )
        val old = Any()
        val replacement = Any()
        try {
            policy.acquire(old)
            policy.acquire(replacement)
            policy.release(old)
            assertTrue(!restored.await(150, TimeUnit.MILLISECONDS))
            policy.release(replacement)
            assertTrue(restored.await(2, TimeUnit.SECONDS))
        } finally {
            policy.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `disabled auto restore keeps the disconnected device journaled after release`() {
        val disconnected = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val controller = object : GeelyA2dpController {
            override fun disconnectSingleConnected(): GeelyA2dpDisconnectResult {
                disconnected.countDown()
                return GeelyA2dpDisconnectResult.Disconnected("AA:BB:CC:DD:EE:FF")
            }

            override fun restore(addresses: Set<String>): GeelyA2dpRestoreResult {
                restored.countDown()
                return GeelyA2dpRestoreResult(addresses, emptySet(), "restored")
            }
        }
        val worker = Executors.newSingleThreadScheduledExecutor()
        val statuses = mutableListOf<String>()
        val policy = GeelyBluetoothAudioConflictPolicy(
            controller,
            GeelyBluetoothMediaJournal(context),
            worker,
            onStatus = statuses::add,
            restoreEnabled = { false },
            pollMillis = 60_000,
        )
        val owner = Any()
        try {
            policy.acquire(owner)
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (GeelyBluetoothMediaJournal(context).pending().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }

            policy.release(owner)

            assertFalse(restored.await(200, TimeUnit.MILLISECONDS))
            assertEquals(setOf("AA:BB:CC:DD:EE:FF"), GeelyBluetoothMediaJournal(context).pending())
            assertTrue(statuses.any { it.contains("auto-restore disabled") })
        } finally {
            policy.close()
            worker.shutdownNow()
        }
    }
}
