package com.lllhys.geeplay.vehicle.geely

/** Shell transport is supplied by the setup flow; credentials never enter persisted settings. */
internal fun interface GeelyQnxCommandPort {
    fun execute(command: String): String
    fun executeBatch(commands: List<String>) { commands.forEach { execute(it) } }
}

/** Owns only explicitly provisioned GeePlay processes, never an AtlasDim process. */
internal class GeelyQnxDaemonController(private val shell: GeelyQnxCommandPort) {
    private val owned = mutableMapOf<Int, Int>()

    fun install(bytes: ByteArray, port: Int, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(port == 53281 || port == 53283)
        require(bytes.isNotEmpty() && bytes.size <= 1_048_576)
        val binary = "/tmp/geeplay-navd-$port"
        val expected = GeelyQnxBinary.sha256(bytes)
        if (runCatching { shell.execute("openssl sha256 $binary").contains(expected) }.getOrDefault(false)) {
            shell.execute("chmod 755 $binary")
            return
        }
        val temp = "$binary-${java.util.UUID.randomUUID().toString().take(8)}.new"
        try {
            shell.execute(": > $temp")
            val commands = bytes.asList().chunked(24).map { chunk ->
                val escaped = chunk.joinToString("") { "\\0" + (it.toInt() and 255).toString(8).padStart(3,'0') }
                "print -n '$escaped' >> $temp"
            }
            for ((index, batch) in commands.chunked(8).withIndex()) {
                shell.executeBatch(batch)
                if (index % 10 == 0) progress(minOf((index+1)*8*24,bytes.size),bytes.size)
            }
            check(shell.execute("openssl sha256 $temp").contains(expected)) { "QNX component transfer checksum mismatch" }
            shell.execute("chmod 755 $temp")
            check("ghudbridgelited/" in shell.execute("$temp --version 2>&1")) { "Unsupported QNX component" }
            shell.execute("mv -f $temp $binary")
            progress(bytes.size,bytes.size)
        } catch (error: Exception) {
            runCatching { shell.execute("rm -f $temp") }
            throw error
        }
    }

    @Synchronized fun start(port: Int, z: Int): Int {
        require(port == 53281 || port == 53283)
        require(z in 0..8)
        recover(port)?.let { return it }
        val binary = "/tmp/geeplay-navd-$port"
        // The setup flow must supply a daemon patched for the selected QNX display.
        // Do not discover or stop another application's process to acquire this port.
        val result = shell.execute("test -x $binary || exit 1; $binary --port $port --z $z > /tmp/geeplay-navd-$port.log 2>&1 & echo GEEPLAY_PID=\$!")
        val pid = Regex("(?m)^GEEPLAY_PID=([0-9]+)\\r?$").find(result)?.groupValues?.get(1)?.toIntOrNull()
        check(pid != null && pid > 1) { "QNX did not report a daemon PID" }
        owned[port] = pid
        return pid
    }

    @Synchronized fun stop(port: Int) {
        require(port == 53281 || port == 53283)
        val pid = owned[port] ?: recover(port) ?: return
        // Verify PID identity immediately before kill; a recycled PID must not be targeted.
        val identity = shell.execute("pidin -p $pid ar")
        val expected = "/tmp/geeplay-navd-$port"
        check(identity.lineSequence().any { line ->
            val words = line.trim().split(Regex("\\s+"))
            words.firstOrNull() == pid.toString() && expected in words &&
                words.windowed(2).any { it == listOf("--port", port.toString()) }
        }) { "QNX daemon identity changed; refusing to stop PID" }
        shell.execute("kill $pid")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (recover(port) != null) {
            check(System.nanoTime() < deadline) { "QNX daemon did not stop" }
            Thread.sleep(50)
        }
        owned.remove(port)
    }

    private fun recover(port: Int): Int? {
        val expected = "/tmp/geeplay-navd-$port"
        val matches = shell.execute("pidin ar").lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { words -> expected in words && words.windowed(2).any { it == listOf("--port", port.toString()) } }
            .mapNotNull { it.firstOrNull()?.toIntOrNull()?.takeIf { pid -> pid > 1 } }.distinct().toList()
        check(matches.size <= 1) { "Multiple GeePlay processes found for port" }
        return matches.singleOrNull()?.also { owned[port] = it }
    }
}
