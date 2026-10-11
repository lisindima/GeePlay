package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import java.io.Closeable

interface GeelyNavigationDriver : Closeable {
    val failed: Boolean
    val cleanupPending: Boolean
    fun setExecutionGuard(guard: () -> Boolean)
    fun start(): Boolean
    fun publish(snapshot: AppleNavigationSnapshot): Boolean
    fun clear(): Boolean
    fun recoverCleanup(): Boolean
}

enum class GeelyNavigationDriverState {
    IDLE,
    INITIALIZING,
    WAITING_FOR_SERVICE,
    READY,
    ACTIVE,
    CLEARING,
    FAILED,
    CLEANUP_PENDING,
    CLOSED,
}

data class GeelyNavigationDriverStatus(
    val state: GeelyNavigationDriverState,
    val stage: String,
    val failureType: String? = null,
)
