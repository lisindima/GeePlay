package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyNavigationRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `app-open recovery waits for an asynchronous watched connection`() {
        val journal = GeelyNavigationRecoveryJournal(context).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val attempts = AtomicInteger()
        var closed = false
        val driver = driver(
            recover = { attempts.incrementAndGet() >= 2 },
            close = { closed = true },
        )

        assertTrue(GeelyNavigationRecovery.recoverPending(context, retryMillis = 0) { driver })
        assertEquals(2, attempts.get())
        assertTrue(closed)
        assertNull(journal.load())
    }

    @Test
    fun `failed app-open recovery preserves the durable cleanup marker`() {
        val journal = GeelyNavigationRecoveryJournal(context).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val driver = object : GeelyNavigationDriver {
            override val failed = true
            override val cleanupPending = true
            override fun setExecutionGuard(guard: () -> Boolean) = Unit
            override fun start(): Boolean = false
            override fun publish(snapshot: AppleNavigationSnapshot): Boolean = false
            override fun clear(): Boolean = false
            override fun recoverCleanup(): Boolean = false
            override fun close() = Unit
        }

        assertFalse(GeelyNavigationRecovery.recoverPending(context, retryMillis = 0) { driver })
        assertTrue(journal.load() != null)
        journal.clear()
    }

    private fun driver(recover: () -> Boolean, close: () -> Unit) = object : GeelyNavigationDriver {
        override val failed = false
        override val cleanupPending = false
        override fun setExecutionGuard(guard: () -> Boolean) = Unit
        override fun start(): Boolean = true
        override fun publish(snapshot: AppleNavigationSnapshot): Boolean = true
        override fun clear(): Boolean = true
        override fun recoverCleanup(): Boolean = recover()
        override fun close() = close()
    }

    private fun config() = GeelyNavigationConfig(
        enabled = true,
        backend = GeelyNavigationBackend.AUTO,
        laneGuidanceEnabled = true,
        originalOneOsIcons = false,
        laneNumberBase = 0,
    )
}
