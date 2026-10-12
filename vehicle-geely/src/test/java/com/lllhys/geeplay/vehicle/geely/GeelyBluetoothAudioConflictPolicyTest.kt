package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyBluetoothAudioConflictPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun clearJournal() = GeelyBluetoothMediaJournal(context).replace(emptySet())

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
}
