package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort recovery of a presentation left active by process death. */
object GeelyNavigationRecovery {
    private val running = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "geeplay-geely-recovery").apply { isDaemon = true }
    }

    fun restoreOnAppOpen(context: Context) {
        val appContext = context.applicationContext
        val journal = GeelyNavigationRecoveryJournal(appContext)
        if (journal.load() == null || !running.compareAndSet(false, true)) return
        worker.execute {
            try {
                recoverPending(appContext)
            } finally {
                running.set(false)
            }
        }
    }

    internal fun recoverPending(
        context: Context,
        retryMillis: Long = RETRY_MILLIS,
        driverFactory: (GeelyNavigationConfig) -> GeelyNavigationDriver = { config ->
            GeelyDimNavigationAdapter(
                context.applicationContext,
                config,
                onStatus = GeelyNavigationStatusStore::update,
            )
        },
    ): Boolean = synchronized(GeelyNavigationRecoveryJournal.recoveryLock) {
        val journal = GeelyNavigationRecoveryJournal(context.applicationContext)
        val entry = journal.load() ?: return@synchronized true
        val driver = runCatching { driverFactory(entry.config()) }.getOrElse { error ->
            GeelyNavigationStatusStore.update(
                GeelyNavigationDriverStatus(
                    GeelyNavigationDriverState.CLEANUP_PENDING,
                    "could not create OEM navigation recovery driver",
                    error.javaClass.simpleName,
                ),
            )
            return@synchronized false
        }
        var recovered = false
        var closedCleanly = false
        try {
            driver.setExecutionGuard { true }
            for (attempt in 0 until MAX_RECOVERY_ATTEMPTS) {
                recovered = driver.recoverCleanup() && !driver.cleanupPending
                if (recovered || driver.failed || driver.cleanupPending) break
                if (attempt + 1 < MAX_RECOVERY_ATTEMPTS) Thread.sleep(retryMillis)
            }
        } finally {
            closedCleanly = runCatching { driver.close() }.isSuccess
        }
        val confirmed = recovered && closedCleanly && !driver.cleanupPending
        if (confirmed) journal.clear()
        confirmed
    }

    private const val RETRY_MILLIS = 1_000L
    private const val MAX_RECOVERY_ATTEMPTS = 8
}
