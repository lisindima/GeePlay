package com.lllhys.geeplay.vehicle.geely

import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeelyQnxConnectionTest {
    @Test fun rejectsNonProtocolEndpointAndClosesItsSocket() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit<Unit> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val header = ByteArray(24); input.readFully(header)
                        val (_, length) = GeelyQnxProtocol.replyHeader(header)
                        input.readFully(ByteArray(length))
                        socket.getOutputStream().write(ByteArray(24))
                    }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    GeelyQnxConnection.open(GeelyQnxWindowConfig("127.0.0.1", server.localPort, true), 0, 0, 2, 1)
                }
                task.get(10, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun handshakeFrameAndCloseUseOnlyTheSelectedSocket() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val received = pool.submit<List<Int>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        fun read(): Int {
                            val header = ByteArray(24); input.readFully(header)
                            val (type, length) = GeelyQnxProtocol.replyHeader(header)
                            input.readFully(ByteArray(length))
                            return type
                        }
                        val hello = read()
                        socket.getOutputStream().write(GeelyQnxProtocol.packet(GeelyQnxProtocol.HELLO_ACK, 0, 1, byteArrayOf()))
                        listOf(hello, read(), read())
                    }
                }
                val config = GeelyQnxWindowConfig("127.0.0.1", server.localPort, true)
                GeelyQnxConnection.open(config, 0, 0, 2, 1).use { it.frame(2, 1, ByteArray(8)) }
                assertEquals(listOf(GeelyQnxProtocol.HELLO, GeelyQnxProtocol.FRAME, GeelyQnxProtocol.BYE),
                    received.get(10, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }
}
