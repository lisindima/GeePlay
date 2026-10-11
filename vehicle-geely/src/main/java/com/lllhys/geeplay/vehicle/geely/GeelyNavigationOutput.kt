package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationOutput
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Conflating, single-thread boundary around the potentially blocking ECARX service. */
class GeelyNavigationOutput(
    context: Context,
    private val config: GeelyNavigationConfig,
    private val onStatus: (GeelyNavigationDriverStatus) -> Unit = {},
    private val driverFactory: (GeelyNavigationConfig) -> GeelyNavigationDriver = { selected ->
        GeelyDimNavigationAdapter(context.applicationContext, selected, onStatus = onStatus)
    },
    private val journal: GeelyNavigationRecoveryJournal = GeelyNavigationRecoveryJournal(context),
    private val retryMillis: Long = RETRY_MILLIS,
) : NavigationOutput {
    private data class PublicationKey(val sessionGeneration: Long, val sourceId: Long)

    private sealed interface Work {
        data object Recover : Work
        data object Refresh : Work
        data class Clear(val value: NavigationClear) : Work
        data class Publish(val value: NavigationPublication) : Work
        data object Close : Work
    }

    private val lock = Any()
    private val runtimeControlOwner = Any()
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "geeplay-geely-navigation").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var driver: GeelyNavigationDriver? = null
    private var activeKey: PublicationKey? = null
    private var latestPublication: NavigationPublication? = null
    private var pendingPublication: NavigationPublication? = null
    private var pendingClear: NavigationClear? = null
    private var recoveryRequested = journal.load() != null
    private var recoveryAttempts = 0
    private var cleanupBlocked = recoveryRequested
    private var refreshRequested = false
    private var runtimeEnabled = config.enabled
    private var retryFuture: ScheduledFuture<*>? = null
    private var draining = false
    private var closing = false
    private var closed = false

    init {
        GeelyNavigationRuntimeControl.attach(runtimeControlOwner, ::requestRefresh, ::requestDisable)
        synchronized(lock) { scheduleLocked() }
    }

    override fun publish(publication: NavigationPublication) {
        synchronized(lock) {
            if (!runtimeEnabled || closing || closed) return
            cancelRetryLocked()
            activeKey = PublicationKey(publication.sessionGeneration, publication.source.id)
            latestPublication = publication
            if (cleanupBlocked) return
            pendingPublication = publication
            scheduleLocked()
        }
    }

    override fun clear(clear: NavigationClear) {
        synchronized(lock) {
            if (closed) return
            cancelRetryLocked()
            activeKey = null
            latestPublication = null
            pendingPublication = null
            pendingClear = clear
            if (cleanupBlocked) recoveryRequested = true
            scheduleLocked()
        }
    }

    override fun close() {
        GeelyNavigationRuntimeControl.detach(runtimeControlOwner)
        synchronized(lock) {
            if (closing || closed) return
            closing = true
            cancelRetryLocked()
            activeKey = null
            latestPublication = null
            pendingPublication = null
            scheduleLocked()
        }
    }

    private fun scheduleLocked() {
        if (draining || closed) return
        if (!recoveryRequested && !refreshRequested && pendingClear == null && pendingPublication == null && !closing) return
        draining = true
        try {
            executor.execute(::drain)
        } catch (_: RejectedExecutionException) {
            draining = false
            closed = true
        }
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                when {
                    recoveryRequested -> {
                        recoveryRequested = false
                        Work.Recover
                    }
                    refreshRequested -> {
                        refreshRequested = false
                        Work.Refresh
                    }
                    pendingClear != null -> Work.Clear(pendingClear!!.also { pendingClear = null })
                    pendingPublication != null -> Work.Publish(pendingPublication!!.also { pendingPublication = null })
                    closing -> {
                        closing = false
                        closed = true
                        Work.Close
                    }
                    else -> {
                        draining = false
                        return
                    }
                }
            }
            when (work) {
                Work.Recover -> recover()
                Work.Refresh -> refreshDriver()
                is Work.Clear -> clearDriver()
                is Work.Publish -> publishNow(work.value)
                Work.Close -> {
                    if (driver != null) clearDriver()
                    runCatching { driver?.close() }
                    driver = null
                    executor.shutdown()
                    return
                }
            }
        }
    }

    private fun recover() {
        synchronized(GeelyNavigationRecoveryJournal.recoveryLock) {
            val entry = journal.load()
            if (entry == null) {
                synchronized(lock) {
                    cleanupBlocked = false
                    recoveryAttempts = 0
                }
                return@synchronized
            }
            val recoveryDriver = driver ?: driverFactory(entry.config()).also { driver = it }
            recoveryDriver.setExecutionGuard { synchronized(lock) { !closed && !closing } }
            recoveryAttempts += 1
            val cleared = runCatching { recoveryDriver.recoverCleanup() }.getOrDefault(false)
            if (cleared && !recoveryDriver.cleanupPending) {
                val closedCleanly = runCatching { recoveryDriver.close() }.isSuccess
                driver = null
                if (closedCleanly && !recoveryDriver.cleanupPending) {
                    journal.clear()
                    synchronized(lock) {
                        cleanupBlocked = false
                        recoveryAttempts = 0
                        latestPublication?.let { latest ->
                            val key = PublicationKey(latest.sessionGeneration, latest.source.id)
                            if (!closed && activeKey == key) pendingPublication = latest
                        }
                    }
                    onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.IDLE, "recovered interrupted OEM navigation"))
                } else {
                    synchronized(lock) { cleanupBlocked = true }
                    onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.CLEANUP_PENDING, "OEM navigation recovery close was not confirmed"))
                }
            } else if (!recoveryDriver.failed && !recoveryDriver.cleanupPending &&
                recoveryAttempts < MAX_RECOVERY_ATTEMPTS
            ) {
                scheduleRecoveryRetry()
                onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.CLEARING, "waiting to retry OEM navigation recovery"))
            } else {
                runCatching { recoveryDriver.close() }
                driver = null
                synchronized(lock) { cleanupBlocked = true }
                onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.CLEANUP_PENDING, "OEM navigation recovery still pending"))
            }
        }
    }

    private fun requestRefresh() {
        synchronized(lock) {
            if (closing || closed) return
            if (journal.load() != null) {
                cleanupBlocked = true
                recoveryAttempts = 0
                recoveryRequested = true
            } else {
                refreshRequested = true
            }
            scheduleLocked()
        }
    }

    private fun requestDisable() {
        synchronized(lock) {
            if (closing || closed || !runtimeEnabled) return
            runtimeEnabled = false
            cancelRetryLocked()
            activeKey = null
            latestPublication = null
            pendingPublication = null
            pendingClear = NavigationClear(null, 0, com.shilapi.xcertplay.navigation.runtime.NavigationClearReason.SHUTDOWN)
            scheduleLocked()
        }
    }

    private fun refreshDriver() {
        val old = driver
        driver = null
        runCatching { old?.close() }
        if (old?.cleanupPending == true) {
            synchronized(lock) {
                cleanupBlocked = true
                recoveryRequested = true
            }
            onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.CLEANUP_PENDING, "navigation cleanup retry required"))
            return
        }
        journal.clear()
        synchronized(lock) {
            latestPublication?.let { latest ->
                val key = PublicationKey(latest.sessionGeneration, latest.source.id)
                if (!closed && activeKey == key) pendingPublication = latest
            }
        }
        onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.IDLE, "navigation interfaces refreshed"))
    }

    private fun publishNow(publication: NavigationPublication) {
        val key = PublicationKey(publication.sessionGeneration, publication.source.id)
        if (synchronized(lock) { closed || activeKey != key }) return
        val current = driver ?: driverFactory(config).also { driver = it }
        current.setExecutionGuard { synchronized(lock) { !closed && activeKey == key } }
        if (!current.start()) {
            if (!current.failed && !current.cleanupPending) retry(publication)
            return
        }
        if (!journal.mark(config)) {
            onStatus(GeelyNavigationDriverStatus(GeelyNavigationDriverState.FAILED, "could not persist OEM cleanup marker"))
            return
        }
        val published = current.publish(publication.snapshot)
        if (!published) {
            if (current.cleanupPending) {
                synchronized(lock) { cleanupBlocked = true }
            } else {
                journal.clear()
                if (!current.failed) retry(publication)
            }
        } else {
            synchronized(lock) { cancelRetryLocked() }
        }
    }

    private fun clearDriver() {
        val current = driver ?: run {
            if (journal.load() != null) recover()
            return
        }
        val cleared = runCatching { current.clear() }.getOrDefault(false)
        val closedCleanly = runCatching { current.close() }.isSuccess
        driver = null
        if (cleared && closedCleanly && !current.cleanupPending) {
            journal.clear()
        } else {
            synchronized(lock) { cleanupBlocked = true }
        }
    }

    private fun retry(publication: NavigationPublication) {
        try {
            synchronized(lock) {
                cancelRetryLocked()
                if (closed || closing || cleanupBlocked) return
                retryFuture = executor.schedule(
                {
                    synchronized(lock) {
                        retryFuture = null
                        val key = PublicationKey(publication.sessionGeneration, publication.source.id)
                        if (!closed && !closing && !cleanupBlocked && activeKey == key) {
                            pendingPublication = latestPublication?.takeIf {
                                PublicationKey(it.sessionGeneration, it.source.id) == key
                            }
                            scheduleLocked()
                        }
                    }
                },
                retryMillis,
                TimeUnit.MILLISECONDS,
            )
            }
        } catch (_: RejectedExecutionException) {
            // Teardown won the race; the stale publication must not be retried.
        }
    }

    private fun scheduleRecoveryRetry() {
        try {
            synchronized(lock) {
                cancelRetryLocked()
                if (closed || closing) return
                retryFuture = executor.schedule(
                    {
                        synchronized(lock) {
                            retryFuture = null
                            if (!closed && !closing && cleanupBlocked) {
                                recoveryRequested = true
                                scheduleLocked()
                            }
                        }
                    },
                    retryMillis,
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (_: RejectedExecutionException) {
            // Teardown won the race; the journal remains for the next app start.
        }
    }

    private fun cancelRetryLocked() {
        retryFuture?.cancel(false)
        retryFuture = null
    }

    internal companion object {
        const val RETRY_MILLIS = 1_000L
        const val MAX_RECOVERY_ATTEMPTS = 8
    }
}
