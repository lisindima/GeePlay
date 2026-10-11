package com.shilapi.xcertplay.navigation.runtime

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGuidanceCoordinatorTest {
    private val output = RecordingOutput()
    private val coordinator = RouteGuidanceCoordinator(listOf(output))
    private val session = Any()

    @Test
    fun `source does not publish before it is ready and session is active`() {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        coordinator.sourceOpened(source)
        coordinator.onFrame(source, maneuver(1, 2))
        coordinator.onFrame(source, route(1))
        assertTrue(output.publications.isEmpty())

        coordinator.sourceReady(source)
        assertTrue(output.publications.isEmpty())

        coordinator.sessionStarted(session)
        assertEquals(1, output.publications.size)
        assertEquals(source, output.publications.single().source)
    }

    @Test
    fun `runtime tunnel replaces bootstrap and bootstrap cannot reclaim ownership`() {
        val bootstrap = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRELESS_BLUETOOTH_BOOTSTRAP)
        val tunnel = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRELESS_RUNTIME_TUNNEL)
        coordinator.sourceOpened(bootstrap)
        coordinator.sourceReady(bootstrap)
        coordinator.sessionStarted(session)
        coordinator.onFrame(bootstrap, maneuver(1, 1))
        coordinator.onFrame(bootstrap, route(1))
        assertEquals(1, output.publications.size)

        coordinator.sourceOpened(tunnel)
        coordinator.onFrame(tunnel, maneuver(2, 2))
        coordinator.onFrame(tunnel, route(2))
        coordinator.sourceReady(tunnel)
        assertEquals(NavigationClearReason.SOURCE_REPLACED, output.clears.last().reason)
        assertEquals(tunnel, output.publications.last().source)

        val publicationsAfterPromotion = output.publications.size
        coordinator.onFrame(bootstrap, maneuver(1, 3))
        coordinator.onFrame(bootstrap, route(1))
        assertEquals(publicationsAfterPromotion, output.publications.size)

        coordinator.sourceClosed(tunnel)
        coordinator.onFrame(bootstrap, maneuver(1, 4))
        assertEquals(publicationsAfterPromotion, output.publications.size)
        assertEquals(NavigationClearReason.SOURCE_CLOSED, output.clears.last().reason)
    }

    @Test
    fun `old session end cannot clear replacement session`() {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        coordinator.sourceOpened(source)
        coordinator.sourceReady(source)
        coordinator.onFrame(source, maneuver(1, 1))
        coordinator.onFrame(source, route(1))
        val first = Any()
        val second = Any()
        coordinator.sessionStarted(first)
        coordinator.sessionStarted(second)
        val clears = output.clears.size

        coordinator.sessionEnded(first)

        assertEquals(clears, output.clears.size)
    }

    @Test
    fun `session replacement clears cached route instead of publishing it into the new session`() {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        coordinator.sourceOpened(source)
        coordinator.sourceReady(source)
        coordinator.onFrame(source, maneuver(1, 1))
        coordinator.onFrame(source, route(1, laneIndex = 3))
        coordinator.onFrame(source, laneEvent(3))
        val first = Any()
        val second = Any()
        coordinator.sessionStarted(first)
        val publications = output.publications.size

        coordinator.sessionStarted(second)

        assertEquals(publications, output.publications.size)
        assertEquals(NavigationClearReason.SESSION_ENDED, output.clears.last().reason)
        coordinator.onFrame(source, route(1, laneIndex = 3))
        assertEquals("A maneuver from the old session must not be reused", publications, output.publications.size)
    }

    @Test
    fun `ended session does not leak cached route into a later session`() {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        coordinator.sourceOpened(source)
        coordinator.sourceReady(source)
        coordinator.onFrame(source, maneuver(1, 1))
        coordinator.onFrame(source, route(1))
        coordinator.sessionStarted(session)
        val publications = output.publications.size

        coordinator.sessionEnded(session)
        coordinator.sessionStarted(Any())

        assertEquals(publications, output.publications.size)
    }

    @Test
    fun `future lane update is cached without an output publication`() {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        coordinator.sourceOpened(source)
        coordinator.sourceReady(source)
        coordinator.sessionStarted(session)
        coordinator.onFrame(source, maneuver(1, 1))
        coordinator.onFrame(source, route(1, laneIndex = 3))
        val publications = output.publications.size

        coordinator.onFrame(source, laneEvent(8))

        assertEquals(publications, output.publications.size)
        coordinator.onFrame(source, route(1, laneIndex = 8))
        assertEquals(8, output.publications.last().snapshot.laneGuidance?.eventIndex)
    }

    @Test
    fun `refresh clears an expired route`() {
        var now = 0L
        val timed = RouteGuidanceCoordinator(
            listOf(output),
            storeFactory = { source ->
                com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceStore(
                    generation = source.id,
                    nanoTime = { now },
                )
            },
        )
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        timed.sourceOpened(source)
        timed.sourceReady(source)
        timed.sessionStarted(session)
        timed.onFrame(source, maneuver(1, 1))
        timed.onFrame(source, route(1))
        now = com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceStore.DEFAULT_STALE_ROUTE_NANOS

        timed.refresh()

        assertEquals(NavigationClearReason.STALE, output.clears.last().reason)
    }

    @Test
    fun `session clear cannot overtake a publication already being dispatched`() {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val ordered = ArrayList<String>()
        val blocking = object : NavigationOutput {
            override fun publish(publication: NavigationPublication) {
                publishEntered.countDown()
                assertTrue(releasePublish.await(5, TimeUnit.SECONDS))
                synchronized(ordered) { ordered += "publish" }
            }

            override fun clear(clear: NavigationClear) {
                synchronized(ordered) { ordered += "clear" }
            }
        }
        val concurrent = RouteGuidanceCoordinator(listOf(blocking))
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        concurrent.sourceOpened(source)
        concurrent.sourceReady(source)
        concurrent.sessionStarted(session)
        concurrent.onFrame(source, maneuver(1, 1))
        val publisher = Thread { concurrent.onFrame(source, route(1)) }
        val closer = Thread { concurrent.sessionEnded(session) }

        publisher.start()
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))
        closer.start()
        releasePublish.countDown()
        publisher.join(5_000)
        closer.join(5_000)

        assertTrue("Publisher did not finish", !publisher.isAlive)
        assertTrue("Session closer did not finish", !closer.isAlive)
        assertEquals(listOf("publish", "clear"), ordered)
        concurrent.close()
    }

    private class RecordingOutput : NavigationOutput {
        val publications = ArrayList<NavigationPublication>()
        val clears = ArrayList<NavigationClear>()
        override fun publish(publication: NavigationPublication) { publications += publication }
        override fun clear(clear: NavigationClear) { clears += clear }
    }

    private fun maneuver(index: Int, type: Int): Iap2Frame =
        Iap2Messages.buildRaw(0x5202) { u16(1, index); u8(3, type) }

    private fun route(index: Int, laneIndex: Int? = null): Iap2Frame =
        Iap2Messages.buildRaw(0x5201) {
            u8(1, 1)
            u32(0x0a, 50)
            u16List(0x0d, listOf(index))
            if (laneIndex != null) {
                u16(0x10, laneIndex)
                u8(0x12, 1)
            }
        }

    private fun laneEvent(index: Int): Iap2Frame = Iap2Frame(
        0x5204,
        tlvs(
            tlv(1, 0, index),
            tlvBytes(2, tlvs(tlv(0, 0, 0), tlv(1, 2), tlv(2, 0, 0))),
        ),
    )

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvBytes(type: Int, value: ByteArray): ByteArray =
        tlv(type, *value.map { it.toInt() and 0xff }.toIntArray())

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}
