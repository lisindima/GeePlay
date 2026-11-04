package com.lllhys.geeplay.vehicle.geely

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.CRC32
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One connection to a separately managed GeePlay daemon. Never touches AtlasDim processes. */
internal class GeelyQnxConnection private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private var sequence = 0
    @Volatile private var closed = false

    private fun watchReplies() {
        socket.soTimeout = 0
        Thread({
            try {
                while (!closed) {
                    val type = readReply()
                    if (type == GeelyQnxProtocol.ERROR || type == GeelyQnxProtocol.BYE) break
                }
            } catch (_: IOException) {
                // The next send reports the closed connection to its owner.
            } catch (_: IllegalArgumentException) {
                // Invalid protocol data ends this session.
            } finally {
                closed = true
                runCatching { socket.close() }
            }
        }, "geeplay-qnx-replies").apply { isDaemon = true; start() }
    }

    @Synchronized private fun send(type: Int, flags: Int = 0, payload: ByteArray = byteArrayOf()) {
        check(!closed)
        val timeout = writeTimeouts.schedule({ runCatching { socket.close() } }, 5, TimeUnit.SECONDS)
        try {
            output.write(GeelyQnxProtocol.packet(type, flags, ++sequence, payload))
            output.flush()
        } finally { timeout.cancel(false) }
    }

    private fun readReply(): Int {
        val header = ByteArray(GeelyQnxProtocol.HEADER_SIZE)
        input.readFully(header)
        val (type, length) = GeelyQnxProtocol.replyHeader(header)
        val payload = ByteArray(length)
        input.readFully(payload)
        val expected = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(16)
        val actual = CRC32().apply { update(payload) }.value.toInt()
        if (expected != actual) throw IOException("Invalid gcp/1 reply checksum")
        return type
    }

    fun frame(width: Int, height: Int, rgba: ByteArray) =
        send(GeelyQnxProtocol.FRAME, GeelyQnxProtocol.FRAME_LAST, GeelyQnxProtocol.rgbaFrame(width, height, rgba))

    fun ping() = send(GeelyQnxProtocol.PING)

    @Synchronized override fun close() {
        if (closed) return
        try { send(GeelyQnxProtocol.BYE) } catch (_: Exception) { }
        closed = true
        socket.close()
    }

    companion object {
        private val writeTimeouts = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "geeplay-qnx-write-timeout").apply { isDaemon = true }
        }
        fun open(config: GeelyQnxWindowConfig, x: Int, y: Int, width: Int, height: Int): GeelyQnxConnection {
            require(config.enabled)
            val hello = GeelyQnxProtocol.hello(x, y, width, height)
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(config.host, config.port), 3000)
                socket.soTimeout = 5000
                socket.tcpNoDelay = true
                val connection = GeelyQnxConnection(socket)
                connection.send(GeelyQnxProtocol.HELLO, payload = hello)
                if (connection.readReply() != GeelyQnxProtocol.HELLO_ACK) throw IOException("QNX window rejected HELLO")
                connection.watchReplies()
                return connection
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }
}
