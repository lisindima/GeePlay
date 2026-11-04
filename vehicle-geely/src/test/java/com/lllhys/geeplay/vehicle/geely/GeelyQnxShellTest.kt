package com.lllhys.geeplay.vehicle.geely

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GeelyQnxShellTest {
    @Test fun logsInExecutesChecksExitCodeAndErasesPassword() = exerciseLogin("Password:")
    @Test fun supportsPasswordPromptWithoutColonLikeAtlasDim() = exerciseLogin("Password")

    private fun exerciseLogin(passwordPrompt: String) {
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
                        send(passwordPrompt)
                        assertEquals("test-secret", reader.readLine())
                        send("\n# ")
                        repeat(3) { index ->
                            val beginCommand = reader.readLine()
                            val command = reader.readLine()
                            val endCommand = reader.readLine()
                            val begin = beginCommand.removePrefix("echo ")
                            val marker = Regex("GP_[a-f0-9]+_END:").find(endCommand)!!.value
                            // Echo includes the marker text but must not terminate the response.
                            send(beginCommand + "\n# " + begin + "\n" + command + "\n" + endCommand + "\n" + if (index == 1) "payload\n" else "")
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
