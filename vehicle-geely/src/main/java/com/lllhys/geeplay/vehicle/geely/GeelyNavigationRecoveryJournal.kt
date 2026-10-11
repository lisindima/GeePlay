package com.lllhys.geeplay.vehicle.geely

import android.content.Context

/** Durable marker used to restore the OEM navigation presentation after process death. */
class GeelyNavigationRecoveryJournal(context: Context) {
    data class Entry(
        val backend: GeelyNavigationBackend,
        val laneGuidanceEnabled: Boolean,
        val originalOneOsIcons: Boolean,
        val laneNumberBase: Int,
    ) {
        fun config(): GeelyNavigationConfig = GeelyNavigationConfig(
            enabled = true,
            backend = backend,
            laneGuidanceEnabled = laneGuidanceEnabled,
            originalOneOsIcons = originalOneOsIcons,
            laneNumberBase = laneNumberBase,
        )
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): Entry? {
        if (!preferences.getBoolean(KEY_PENDING, false)) return null
        val backend = preferences.getString(KEY_BACKEND, null)
            ?.let { saved -> GeelyNavigationBackend.entries.firstOrNull { it.name == saved } }
            ?: GeelyNavigationBackend.AUTO
        return Entry(
            backend = backend,
            laneGuidanceEnabled = preferences.getBoolean(KEY_LANES, false),
            originalOneOsIcons = preferences.getBoolean(KEY_RAW_ICONS, false),
            laneNumberBase = preferences.getInt(KEY_LANE_BASE, 0).coerceIn(0, 1),
        )
    }

    fun mark(config: GeelyNavigationConfig): Boolean = preferences.edit()
        .putBoolean(KEY_PENDING, true)
        .putString(KEY_BACKEND, config.backend.name)
        .putBoolean(KEY_LANES, config.laneGuidanceEnabled)
        .putBoolean(KEY_RAW_ICONS, config.originalOneOsIcons)
        .putInt(KEY_LANE_BASE, config.laneNumberBase)
        .commit()

    fun clear(): Boolean = preferences.edit().clear().commit()

    companion object {
        internal val recoveryLock = Any()
        private const val PREFERENCES = "geeplay_geely_navigation_recovery"
        private const val KEY_PENDING = "pending"
        private const val KEY_BACKEND = "backend"
        private const val KEY_LANES = "lanes"
        private const val KEY_RAW_ICONS = "raw_icons"
        private const val KEY_LANE_BASE = "lane_base"
    }
}
