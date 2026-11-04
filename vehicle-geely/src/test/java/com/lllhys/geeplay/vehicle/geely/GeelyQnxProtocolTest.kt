package com.lllhys.geeplay.vehicle.geely

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GeelyQnxProtocolTest {
    @Test fun ambiguousManeuversNeverBecomeFalseStraightArrows() {
        assertEquals("←", maneuverSymbol(1))
        assertEquals("→", maneuverSymbol(2))
        assertEquals("↑", maneuverSymbol(3))
        assertEquals("", maneuverSymbol(6))
        assertEquals("", maneuverSymbol(999))
        assertEquals("950 m", distanceLabel(950))
        assertEquals("1.5 km", distanceLabel(1500))
    }
    @Test fun packetRoundTripAndCorruptionAreChecked() {
        val packet = GeelyQnxProtocol.packet(GeelyQnxProtocol.HELLO_ACK, 0, 4, byteArrayOf(1, 2, 3))
        assertEquals(GeelyQnxProtocol.HELLO_ACK to 3, GeelyQnxProtocol.replyHeader(packet.copyOf(24)))
        packet[5] = 42
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxProtocol.replyHeader(packet.copyOf(24)) }
    }

    @Test fun helloCarriesCoordinatesAndBoundsAreEnforced() {
        val payload = ByteBuffer.wrap(GeelyQnxProtocol.hello(12, 34, 320, 120)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, payload.getInt(4)); assertEquals(34, payload.getInt(8))
        assertEquals(320, payload.getInt(12)); assertEquals(120, payload.getInt(16))
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxProtocol.hello(1900, 0, 40, 50) }
    }

    @Test fun frameRequiresOneRgbaPixelPerPosition() {
        val frame = GeelyQnxProtocol.rgbaFrame(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(32, frame.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.copyOfRange(24, 32))
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxProtocol.rgbaFrame(2, 1, ByteArray(7)) }
    }
}
