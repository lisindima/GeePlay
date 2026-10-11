package com.lllhys.geeplay.vehicle.geely

import java.util.concurrent.atomic.AtomicLong

data class GeelyInputDiagnosticSnapshot(
    val bindAttempts: Long,
    val registrations: Long,
    val unregisterAttempts: Long,
    val binderDeaths: Long,
    val quarantines: Long,
    val registered: Boolean,
)

/** Process-local counters only; no key values, Binder identities, or package data are retained. */
object GeelyInputDiagnostics {
    private val bindAttempts = AtomicLong()
    private val registrations = AtomicLong()
    private val unregisterAttempts = AtomicLong()
    private val binderDeaths = AtomicLong()
    private val quarantines = AtomicLong()
    @Volatile private var registered = false

    fun bindAttempted() { bindAttempts.incrementAndGet() }
    fun registered() { registrations.incrementAndGet(); registered = true }
    fun unregisterAttempted() { unregisterAttempts.incrementAndGet() }
    fun unregistered() { registered = false }
    fun binderDied() { binderDeaths.incrementAndGet(); registered = false }
    fun quarantined() { quarantines.incrementAndGet(); registered = false }

    fun snapshot(): GeelyInputDiagnosticSnapshot = GeelyInputDiagnosticSnapshot(
        bindAttempts.get(),
        registrations.get(),
        unregisterAttempts.get(),
        binderDeaths.get(),
        quarantines.get(),
        registered,
    )
}
