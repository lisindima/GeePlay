package com.lllhys.geeplay.vehicle.geely

object GeelyNavigationStatusStore {
    @Volatile private var latest = GeelyNavigationDriverStatus(
        GeelyNavigationDriverState.IDLE,
        "navigation output has not started",
    )

    fun update(status: GeelyNavigationDriverStatus) {
        latest = status.copy()
    }

    fun snapshot(): GeelyNavigationDriverStatus = latest.copy()
}
