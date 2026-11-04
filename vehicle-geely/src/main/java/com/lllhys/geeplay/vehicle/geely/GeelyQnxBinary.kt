package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pinned QNX component; only its two verified display-selection instructions are changed. */
internal object GeelyQnxBinary {
    const val ASSET = "qnx/geeplay-navd"
    const val SOURCE_SHA256 = "d369a9a654016cc1b193fc2b483d400cf166958bf7464dc7a528c9aa9ed799b0"
    fun load(context: Context, display: Int): ByteArray =
        forDisplay(context.assets.open(ASSET).use { it.readBytes() }, display)

    fun forDisplay(source: ByteArray, display: Int): ByteArray {
        require(display in 1..5)
        check(sha256(source) == SOURCE_SHA256) { "Unexpected bundled QNX component" }
        val result = source.copyOf()
        val words = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in intArrayOf(0x4964, 0x5090)) {
            val instruction = words.getInt(offset)
            val mask = 0xfff shl 10
            check((instruction and mask.inv()) == 0x7100011f) { "QNX display instruction mismatch" }
            words.putInt(offset, (instruction and mask.inv()) or (display shl 10))
        }
        return result
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
}
