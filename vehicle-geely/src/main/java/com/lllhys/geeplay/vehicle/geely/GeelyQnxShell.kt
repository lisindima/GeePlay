package com.lllhys.geeplay.vehicle.geely

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/** Minimal Telnet command transport. No credential or shell output is logged. */
internal class GeelyQnxShell private constructor(private val socket: Socket) : GeelyQnxCommandPort, Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private var negotiation = 0
    private var verb = 0
    private fun line(value: String) { output.write((value + "\r\n").toByteArray()); output.flush() }
    private fun readUntil(done: (String) -> Boolean): String {
        val result = StringBuilder()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val byte = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (byte < 0) throw IOException("QNX connection closed")
            when (negotiation) {
                0 -> if (byte == 255) negotiation = 1 else if (byte != 0 && byte != 13) result.append(byte.toChar())
                1 -> when (byte) {
                    251, 252, 253, 254 -> { verb = byte; negotiation = 2 }
                    250 -> negotiation = 3
                    else -> negotiation = 0
                }
                2 -> { if (verb == 251 || verb == 253) { output.write(byteArrayOf(255.toByte(), (if (verb == 251) 254 else 252).toByte(), byte.toByte())); output.flush() }; negotiation = 0 }
                3 -> if (byte == 255) negotiation = 4
                4 -> negotiation = if (byte == 240) 0 else 3
            }
            if (result.length > 131072) throw IOException("QNX response too large")
            if (negotiation == 0 && done(result.toString())) return result.toString()
        }
        throw IOException("QNX response timeout")
    }

    @Synchronized override fun execute(command: String): String {
        require('\n' !in command && '\r' !in command)
        val marker = "GP_" + UUID.randomUUID().toString().replace("-", "")
        line("$command; printf '\\n$marker:%s\\n' \"\$?\"")
        val end = Regex("(?m)^$marker:([0-9]+)\\n")
        val response = readUntil { end.containsMatchIn(it) }
        val match = end.find(response) ?: throw IOException("QNX command incomplete")
        if (match.groupValues[1] != "0") throw IOException("QNX command failed (${match.groupValues[1]})")
        return response.substring(0, match.range.first)
    }
    override fun close() = socket.close()

    companion object {
        fun open(host: String, password: CharArray, port: Int = 23): GeelyQnxShell {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 500
                val shell = GeelyQnxShell(socket)
                fun prompt(text: String) = Regex("(?m)(login:|[Pp]assword:|[^\\n]*#\\s*$|[Ii]ncorrect)").containsMatchIn(text)
                var response = shell.readUntil(::prompt)
                if (response.trimEnd().endsWith("login:")) { shell.line("root"); response = shell.readUntil(::prompt) }
                if (response.trimEnd().endsWith("assword:")) { shell.line(String(password)); response = shell.readUntil(::prompt) }
                if (!response.trimEnd().endsWith("#")) throw IOException("QNX login failed")
                shell.execute("stty -echo")
                return shell
            } catch (error: Exception) { socket.close(); throw error }
            finally { password.fill('\u0000') }
        }
    }
}
