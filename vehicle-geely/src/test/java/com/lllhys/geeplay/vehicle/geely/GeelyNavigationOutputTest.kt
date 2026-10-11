package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleManeuver
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationClearReason
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSourceKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
class GeelyNavigationOutputTest {
    @Test
    fun `publishes latest snapshot and clears recovery journal`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also { it.clear() }
        val driver = RecordingDriver()
        val output = GeelyNavigationOutput(app, config(), driverFactory = { driver }, journal = journal)
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        val first = publication(source, revision = 1, distance = 100)
        val second = publication(source, revision = 2, distance = 80)

        output.publish(first)
        output.publish(second)
        assertTrue(driver.published.await(5, TimeUnit.SECONDS))

        assertTrue(driver.started)
        assertEquals(80, driver.lastSnapshot?.nextDistanceMeters)
        assertTrue(journal.load() != null)

        output.clear(NavigationClear(source, 1, NavigationClearReason.ROUTE_ENDED))
        assertTrue(driver.cleared.await(5, TimeUnit.SECONDS))
        assertTrue(driver.closed.await(5, TimeUnit.SECONDS))
        awaitJournalCleared(journal)
        output.close()
    }

    @Test
    fun `disabled output never creates a driver`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also { it.clear() }
        var created = false
        val output = GeelyNavigationOutput(
            app,
            config().copy(enabled = false),
            driverFactory = { created = true; RecordingDriver() },
            journal = journal,
        )

        output.publish(publication(RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED), 1, 100))
        Thread.sleep(50)

        assertFalse(created)
        output.close()
    }

    @Test
    fun `pending journal is recovered before new publications`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val driver = RecordingDriver()
        val output = GeelyNavigationOutput(app, config(), driverFactory = { driver }, journal = journal)

        assertTrue(driver.recovered.await(5, TimeUnit.SECONDS))
        awaitJournalCleared(journal)
        output.close()
    }

    @Test
    fun `unconfirmed recovery keeps the journal and blocks new OEM output`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val recovered = CountDownLatch(1)
        var published = false
        val driver = object : GeelyNavigationDriver {
            override val failed = true
            override val cleanupPending = true
            override fun setExecutionGuard(guard: () -> Boolean) = Unit
            override fun start(): Boolean = false
            override fun publish(snapshot: AppleNavigationSnapshot): Boolean { published = true; return false }
            override fun clear(): Boolean = false
            override fun recoverCleanup(): Boolean { recovered.countDown(); return false }
            override fun close() = Unit
        }
        val output = GeelyNavigationOutput(app, config(), driverFactory = { driver }, journal = journal)
        assertTrue(recovered.await(5, TimeUnit.SECONDS))

        output.publish(publication(RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED), 1, 100))
        Thread.sleep(50)

        assertFalse(published)
        assertTrue(journal.load() != null)
        output.close()
        journal.clear()
    }

    @Test
    fun `watched service recovery retries on one driver before publishing`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val attempts = AtomicInteger()
        val recovered = CountDownLatch(1)
        val driver = object : GeelyNavigationDriver {
            override val failed = false
            override val cleanupPending = false
            override fun setExecutionGuard(guard: () -> Boolean) = Unit
            override fun start(): Boolean = true
            override fun publish(snapshot: AppleNavigationSnapshot): Boolean = true
            override fun clear(): Boolean = true
            override fun recoverCleanup(): Boolean = attempts.incrementAndGet() >= 2
            override fun close() = Unit
        }
        val output = GeelyNavigationOutput(
            app,
            config(),
            onStatus = { if (it.stage.startsWith("recovered")) recovered.countDown() },
            driverFactory = { driver },
            journal = journal,
            retryMillis = 20,
        )

        assertTrue(recovered.await(5, TimeUnit.SECONDS))
        assertEquals(2, attempts.get())
        assertNull(journal.load())
        output.close()
    }

    @Test
    fun `recovery retry count is bounded`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also {
            it.clear()
            assertTrue(it.mark(config()))
        }
        val attempts = AtomicInteger()
        val exhausted = CountDownLatch(1)
        val driver = object : GeelyNavigationDriver {
            override val failed = false
            override val cleanupPending = false
            override fun setExecutionGuard(guard: () -> Boolean) = Unit
            override fun start(): Boolean = false
            override fun publish(snapshot: AppleNavigationSnapshot): Boolean = false
            override fun clear(): Boolean = true
            override fun recoverCleanup(): Boolean { attempts.incrementAndGet(); return false }
            override fun close() = Unit
        }
        val output = GeelyNavigationOutput(
            app,
            config(),
            onStatus = { if (it.stage == "OEM navigation recovery still pending") exhausted.countDown() },
            driverFactory = { driver },
            journal = journal,
            retryMillis = 0,
        )

        assertTrue(exhausted.await(5, TimeUnit.SECONDS))
        assertEquals(GeelyNavigationOutput.MAX_RECOVERY_ATTEMPTS, attempts.get())
        assertTrue(journal.load() != null)
        output.close()
        journal.clear()
    }

    @Test
    fun `runtime disable clears the active driver and rejects later publications`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also { it.clear() }
        val driver = RecordingDriver()
        val output = GeelyNavigationOutput(app, config(), driverFactory = { driver }, journal = journal)
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        output.publish(publication(source, revision = 1, distance = 80))
        assertTrue(driver.published.await(5, TimeUnit.SECONDS))

        assertTrue(GeelyNavigationRuntimeControl.requestDisable())
        assertTrue(driver.cleared.await(5, TimeUnit.SECONDS))
        assertTrue(driver.closed.await(5, TimeUnit.SECONDS))
        output.publish(publication(source, revision = 2, distance = 40))
        Thread.sleep(50)

        assertEquals(80, driver.lastSnapshot?.nextDistanceMeters)
        output.close()
    }

    @Test
    fun `a newer publication replaces the single pending service retry`() {
        val app = RuntimeEnvironment.getApplication()
        val journal = GeelyNavigationRecoveryJournal(app).also { it.clear() }
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val driver = RecordingDriver(startResult = {
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) firstAttempt.countDown()
            attempt > 1
        })
        val output = GeelyNavigationOutput(
            app,
            config(),
            driverFactory = { driver },
            journal = journal,
            retryMillis = 100,
        )
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)

        output.publish(publication(source, revision = 1, distance = 100))
        assertTrue(firstAttempt.await(5, TimeUnit.SECONDS))
        output.publish(publication(source, revision = 2, distance = 80))
        assertTrue(driver.published.await(5, TimeUnit.SECONDS))
        Thread.sleep(250)

        assertEquals(2, attempts.get())
        assertEquals(80, driver.lastSnapshot?.nextDistanceMeters)
        output.close()
    }

    private class RecordingDriver(
        private val startResult: () -> Boolean = { true },
    ) : GeelyNavigationDriver {
        val published = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var started = false
        var lastSnapshot: AppleNavigationSnapshot? = null
        override val failed = false
        override val cleanupPending = false
        override fun setExecutionGuard(guard: () -> Boolean) = Unit
        override fun start(): Boolean { started = true; return startResult() }
        override fun publish(snapshot: AppleNavigationSnapshot): Boolean {
            lastSnapshot = snapshot
            if (snapshot.nextDistanceMeters == 80) published.countDown()
            return true
        }
        override fun clear(): Boolean { cleared.countDown(); return true }
        override fun recoverCleanup(): Boolean { recovered.countDown(); return true }
        override fun close() { closed.countDown() }
    }

    private fun config() = GeelyNavigationConfig(
        enabled = true,
        backend = GeelyNavigationBackend.AUTO,
        laneGuidanceEnabled = true,
        originalOneOsIcons = false,
        laneNumberBase = 0,
    )

    private fun publication(source: RouteGuidanceSource, revision: Long, distance: Int) = NavigationPublication(
        source = source,
        sessionGeneration = 1,
        snapshot = AppleNavigationSnapshot(
            generation = source.id,
            revision = revision,
            routeState = AppleRouteState.ROUTE_SET,
            currentRoad = "",
            maneuver = AppleManeuver(1, 1, 0, "Road", null, null),
            nextDistanceMeters = distance,
            remainingMeters = 1_000,
            remainingSeconds = 120,
            arrivalEpochSeconds = null,
            laneGuidance = null,
            updatedAtNanos = 1,
        ),
    )

    private fun awaitJournalCleared(journal: GeelyNavigationRecoveryJournal) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (journal.load() != null && System.nanoTime() < deadline) Thread.yield()
        assertNull(journal.load())
    }
}
