package com.lllhys.geeplay.vehicle.geely

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class GeelyQnxBinaryTest {
    @Test fun bundledBinaryIsPinnedAndOnlyDisplayInstructionsChange() {
        val source = File("src/main/assets/qnx/geeplay-navd").readBytes()
        assertEquals(GeelyQnxBinary.SOURCE_SHA256, GeelyQnxBinary.sha256(source))
        val offsets = setOf(0x4964,0x4965,0x4966,0x4967,0x5090,0x5091,0x5092,0x5093)
        for (display in 1..5) {
            val patched = GeelyQnxBinary.forDisplay(source,display)
            assertEquals(source.size,patched.size)
            assertTrue(source.indices.filter { source[it] != patched[it] }.all { it in offsets })
            val words = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(display,(words.getInt(0x4964) ushr 10) and 0xfff)
            assertEquals(display,(words.getInt(0x5090) ushr 10) and 0xfff)
        }
        assertThrows(IllegalArgumentException::class.java) { GeelyQnxBinary.forDisplay(source,6) }
        source[10] = (source[10].toInt() xor 1).toByte()
        assertThrows(IllegalStateException::class.java) { GeelyQnxBinary.forDisplay(source,2) }
    }

    private class Receiver(val corrupt: Boolean = false) : GeelyQnxCommandPort {
        val bytes = ByteArrayOutputStream()
        val commands = mutableListOf<String>()
        override fun execute(command: String): String {
            commands.add(command)
            check(command.length <= 190)
            return when {
                command.startsWith("print -n") -> {
                    Regex("\\\\0([0-7]{3})").findAll(command).forEach { bytes.write(it.groupValues[1].toInt(8)) }
                    ""
                }
                command.startsWith("openssl sha256") && command.endsWith(".new") ->
                    if (corrupt) "wrong checksum" else "SHA256(file)= ${GeelyQnxBinary.sha256(bytes.toByteArray())}"
                command.endsWith("--version 2>&1") -> "ghudbridgelited/1"
                else -> ""
            }
        }
    }

    @Test fun installationTransportsEveryByteAndDoesNotTouchOtherWindow() {
        val receiver = Receiver()
        val bytes = ByteArray(256) { it.toByte() }
        GeelyQnxDaemonController(receiver).install(bytes,53281)
        assertArrayEquals(bytes,receiver.bytes.toByteArray())
        assertTrue(receiver.commands.any { it.startsWith("mv -f ") })
        assertFalse(receiver.commands.any { "53283" in it || "AtlasDim" in it || "ghudbridgelited-dim" in it })
    }

    @Test fun corruptedTransferIsNotInstalled() {
        val receiver = Receiver(corrupt = true)
        assertThrows(IllegalStateException::class.java) { GeelyQnxDaemonController(receiver).install(ByteArray(256),53283) }
        assertFalse(receiver.commands.any { it.startsWith("mv -f ") })
        assertTrue(receiver.commands.any { it.startsWith("rm -f ") && it.endsWith(".new") })
    }
}
