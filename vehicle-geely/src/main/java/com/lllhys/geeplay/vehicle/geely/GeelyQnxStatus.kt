package com.lllhys.geeplay.vehicle.geely

import java.util.concurrent.ConcurrentHashMap

internal object GeelyQnxStatus {
    private val states = ConcurrentHashMap<String, String>()
    fun update(output: String, state: String) { states[output] = state }
    fun report(): String = "geelyQnx map=${states["map"] ?: "not_started"} turns=${states["turns"] ?: "not_started"}"
}
