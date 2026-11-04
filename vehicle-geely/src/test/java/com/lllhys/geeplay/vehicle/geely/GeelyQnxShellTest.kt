package com.lllhys.geeplay.vehicle.geely

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GeelyQnxShellTest {
    @Test fun logsInExecutesChecksExitCodeAndErasesPassword() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val simulated = executor.submit {
                    server.soTimeout = 5000
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val reader = input.bufferedReader()
                        val writer = socket.getOutputStream()
                        fun send(value: String) { writer.write(value.toByteArray()); writer.flush() }
                        writer.write(byteArrayOf(255.toByte(), 251.toByte(), 1))
                        send("login:")
                        assertEquals(255, input.read()); assertEquals(254, input.read()); assertEquals(1, input.read())
                        assertEquals("root", reader.readLine())
                        send("Password:")
                        assertEquals("test-secret", reader.readLine())
                        send("\n# ")
                        repeat(3) { index ->
                            val command = reader.readLine()
                            val marker = Regex("GP_[a-f0-9]+:").find(command)!!.value
                            // Echo includes the marker text but must not terminate the response.
                            send(command + "\n" + if (index == 1) "payload\n" else "")
                            send("$marker${if (index == 2) 7 else 0}\n# ")
                        }
                    }
                }
                val password = "test-secret".toCharArray()
                GeelyQnxShell.open("127.0.0.1", password, server.localPort).use { shell ->
                    assertTrue(password.all { it == '\u0000' })
                    assertTrue(shell.execute("echo payload").contains("payload"))
                    assertThrows(java.io.IOException::class.java) { shell.execute("false") }
                }
                simulated.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
