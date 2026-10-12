package com.shilapi.xcertplay.vehicle

import java.io.Closeable

/** Product policy that prevents an OEM Bluetooth media sink from competing with CarPlay audio. */
interface BluetoothAudioConflictPolicy : Closeable {
    /** Starts asynchronous media arbitration for [owner]. Must return without waiting for platform I/O. */
    fun acquire(owner: Any, stillActive: () -> Boolean = { true })

    /** Releases only [owner]'s arbitration and asynchronously restores state changed by this policy. */
    fun release(owner: Any)

    override fun close()

    companion object {
        val NONE: BluetoothAudioConflictPolicy = object : BluetoothAudioConflictPolicy {
            override fun acquire(owner: Any, stillActive: () -> Boolean) = Unit
            override fun release(owner: Any) = Unit
            override fun close() = Unit
        }
    }
}
