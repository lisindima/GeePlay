package com.lllhys.geeplay.vehicle.geely

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.ServerSocket
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class GeelyQnxMapOutputTest {
    @Test fun copiesPlaneWithoutPaddingAndWithoutMovingPosition() {
        val bytes = byteArrayOf(99, 1, 2, 3, 4, 5, 6, 7, 8, 88, 88, 9, 10, 11, 12, 13, 14, 15, 16)
        val input = ByteBuffer.wrap(bytes).apply { position(1) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            copyRgbaPlane(input, 2, 2, 10, 4))
        assertEquals(1, input.position())
        assertThrows(IllegalArgumentException::class.java) { copyRgbaPlane(input, 2, 3, 10, 4) }
    }

    @Test fun repeatedPixelsUseCompressedFrame() {
        val frame = GeelyQnxProtocol.rgbaFrame(128, 1, ByteArray(128 * 4) { 42 })
        val header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, header.getShort(0).toInt())
        assertEquals(512, header.getInt(20))
        assertArrayEquals(byteArrayOf(0xff.toByte(), 42, 42, 42, 42), frame.copyOfRange(24, frame.size))
    }

    @Test fun scalesDecodedFrameIntoSmallerWindow() {
        val pixels = byteArrayOf(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 3, 3, 3, 3),
            scaleRgbaPlane(ByteBuffer.wrap(pixels), 2, 2, 8, 4, 1, 2))
        assertThrows(IllegalArgumentException::class.java) {
            scaleRgbaPlane(ByteBuffer.wrap(pixels), 2, 2, 8, 4, 1921, 1)
        }
    }

    @Test fun sendsMapFrameToIndependentEndpointAndClosesWindow() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val frameReceived = CountDownLatch(1)
                val received = executor.submit<List<Int>> {
                    server.soTimeout = 5000
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val types = mutableListOf<Int>()
                        while (true) {
                            val header = ByteArray(24).also(input::readFully)
                            val (type, length) = GeelyQnxProtocol.replyHeader(header)
                            ByteArray(length).also(input::readFully)
                            types.add(type)
                            if (type == GeelyQnxProtocol.FRAME) frameReceived.countDown()
                            if (type == GeelyQnxProtocol.HELLO) {
                                socket.getOutputStream().write(GeelyQnxProtocol.packet(GeelyQnxProtocol.HELLO_ACK, 0, 1, byteArrayOf()))
                            }
                            if (type == GeelyQnxProtocol.BYE) break
                        }
                        types
                    }
                }
                val output = GeelyQnxMapOutput(GeelyQnxWindowConfig("127.0.0.1", server.localPort, true), 0, 0, 2, 1)
                output.offer(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
                assertEquals(true, frameReceived.await(5, TimeUnit.SECONDS))
                output.close()
                val types = received.get(5, TimeUnit.SECONDS)
                assertEquals(GeelyQnxProtocol.HELLO, types.first())
                assertEquals(true, GeelyQnxProtocol.FRAME in types)
                assertEquals(GeelyQnxProtocol.BYE, types.last())
            } finally { executor.shutdownNow() }
        }
    }
}
