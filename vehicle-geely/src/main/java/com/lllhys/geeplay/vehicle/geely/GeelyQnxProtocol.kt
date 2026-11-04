package com.lllhys.geeplay.vehicle.geely

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.io.ByteArrayOutputStream

/** gcp/1 framing for a separately provisioned QNX endpoint. No daemon is bundled. */
internal object GeelyQnxProtocol {
    const val HELLO = 1
    const val HELLO_ACK = 2
    const val PING = 3
    const val BYE = 5
    const val ERROR = 6
    const val FRAME = 16
    const val FRAME_LAST = 1
    const val HEADER_SIZE = 24
    private const val MAGIC = 0x31504347
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun checksum(bytes: ByteArray, length: Int = bytes.size): Int =
        CRC32().apply { update(bytes, 0, length) }.value.toInt()

    fun packet(type: Int, flags: Int, sequence: Int, payload: ByteArray): ByteArray {
        require(type in 0..65535 && flags in 0..65535)
        val b = buffer(HEADER_SIZE + payload.size)
        b.putInt(MAGIC).putShort(type.toShort()).putShort(flags.toShort())
            .putInt(sequence).putInt(payload.size).putInt(checksum(payload))
        b.putInt(checksum(b.array(), 20)).put(payload)
        return b.array()
    }

    fun hello(x: Int, y: Int, width: Int, height: Int): ByteArray {
        require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            x.toLong() + width <= 1920 && y.toLong() + height <= 532)
        val name = "GeePlay navigation".toByteArray(Charsets.UTF_8)
        val b = buffer(58 + name.size)
        b.putShort(1).putShort(1).putInt(x).putInt(y).putInt(width).putInt(height)
            .putInt(0).putShort(3).putShort(5).putInt(0)
        b.put(name.copyOfRange(0, minOf(name.size, 23)))
        b.position(56)
        b.putShort(name.size.toShort()).put(name)
        return b.array()
    }

    fun rgbaFrame(width: Int, height: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0 && width <= 1920 && height <= 532)
        require(pixels.size.toLong() == width.toLong() * height * 4)
        val compressed = encodePixels(pixels)
        val useCompressed = compressed.size < pixels.size
        val data = if (useCompressed) compressed else pixels
        return buffer(24 + data.size).putShort(if (useCompressed) 1 else 0).putShort(0).putInt(0).putInt(0)
            .putInt(width).putInt(height).putInt(pixels.size).put(data).array()
    }

    /** PackBits over complete RGBA pixels; decoded length is carried in the frame header. */
    internal fun encodePixels(pixels: ByteArray): ByteArray {
        require(pixels.size % 4 == 0)
        val out = ByteArrayOutputStream()
        val count = pixels.size / 4
        fun equal(a: Int, b: Int): Boolean = (0..3).all { pixels[a * 4 + it] == pixels[b * 4 + it] }
        var position = 0
        while (position < count) {
            var repeated = 1
            while (repeated < 128 && position + repeated < count && equal(position, position + repeated)) repeated++
            if (repeated > 1) {
                out.write(0x80 or (repeated - 1))
                out.write(pixels, position * 4, 4)
                position += repeated
            } else {
                val start = position++
                while (position < count && position - start < 128 &&
                    !(position + 1 < count && equal(position, position + 1))) position++
                out.write(position - start - 1)
                out.write(pixels, start * 4, (position - start) * 4)
            }
        }
        return out.toByteArray()
    }

    fun replyHeader(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size == HEADER_SIZE)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == MAGIC && b.getInt(20) == checksum(bytes, 20)) { "Invalid gcp/1 header" }
        val length = b.getInt(12)
        require(length in 0..65536) { "Invalid gcp/1 response length" }
        return (b.getShort(4).toInt() and 0xffff) to length
    }
}
