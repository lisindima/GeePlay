package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import com.shilapi.xcertplay.media.MediaOutputOwner

enum class GeelyNavigationBackend {
    AUTO,
    ECARX_FLYME,
    ONE_OS,
}

enum class GeelyConnectionMode {
    WATCHED_CONNECT,
    DIRECT,
}

data class GeelyNavigationConfig(
    val enabled: Boolean,
    val backend: GeelyNavigationBackend,
    val laneGuidanceEnabled: Boolean,
    val originalOneOsIcons: Boolean,
    val laneNumberBase: Int,
    val semanticJunctionEnabled: Boolean = false,
) {
    init {
        require(laneNumberBase == 0 || laneNumberBase == 1) { "laneNumberBase must be 0 or 1" }
    }

    val connectionMode: GeelyConnectionMode
        get() = if (backend == GeelyNavigationBackend.ONE_OS) {
            GeelyConnectionMode.DIRECT
        } else {
            GeelyConnectionMode.WATCHED_CONNECT
        }
}

object GeelyNavigationSettings {
    private const val PREFERENCES = "geeplay_geely_navigation"
    private const val KEY_ENABLED = "geely_navigation_enabled"
    private const val KEY_BACKEND = "geely_navigation_backend"
    private const val KEY_LANE_GUIDANCE = "geely_lane_guidance_enabled"
    private const val KEY_ORIGINAL_ONE_OS_ICONS = "geely_original_oneos_icons"
    private const val KEY_LANE_NUMBER_BASE = "geely_lane_number_base"
    private const val KEY_SEMANTIC_JUNCTION = "geely_semantic_junction_enabled"

    fun load(context: Context): GeelyNavigationConfig {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val backend = preferences.getString(KEY_BACKEND, null)
            ?.let { saved -> GeelyNavigationBackend.entries.firstOrNull { it.name == saved } }
            ?: GeelyNavigationBackend.AUTO
        return GeelyNavigationConfig(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            backend = backend,
            laneGuidanceEnabled = preferences.getBoolean(KEY_LANE_GUIDANCE, false),
            originalOneOsIcons = preferences.getBoolean(KEY_ORIGINAL_ONE_OS_ICONS, false),
            laneNumberBase = preferences.getInt(KEY_LANE_NUMBER_BASE, 0).coerceIn(0, 1),
            semanticJunctionEnabled = preferences.getBoolean(KEY_SEMANTIC_JUNCTION, false),
        )
    }

    fun save(context: Context, config: GeelyNavigationConfig) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_BACKEND, config.backend.name)
                .putBoolean(KEY_LANE_GUIDANCE, config.laneGuidanceEnabled)
                .putBoolean(KEY_ORIGINAL_ONE_OS_ICONS, config.originalOneOsIcons)
                .putInt(KEY_LANE_NUMBER_BASE, config.laneNumberBase)
                .putBoolean(KEY_SEMANTIC_JUNCTION, config.semanticJunctionEnabled)
                .commit(),
        ) { "Could not persist Geely navigation settings" }
    }
}

object GeelyClusterDisplaySettings {
    private const val PREFERENCES = "geeplay_geely_display"
    private const val KEY_ENABLED = "geely_cluster_display_enabled"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    fun saveEnabled(context: Context, enabled: Boolean) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .commit(),
        ) { "Could not persist Geely cluster display setting" }
    }
}

object GeelyMediaSettings {
    private const val PREFERENCES = "geeplay_geely_media"
    private const val KEY_OWNER = "media_output_owner"

    fun loadOwner(context: Context): MediaOutputOwner {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getString(KEY_OWNER, null)
        saved?.let { value -> MediaOutputOwner.entries.firstOrNull { it.name == value } }?.let { return it }
        val legacy = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        val migrated = if (legacy.contains(LEGACY_OEM_BLUETOOTH_KEY)) {
            if (legacy.getBoolean(LEGACY_OEM_BLUETOOTH_KEY, true)) {
                MediaOutputOwner.OEM_BLUETOOTH
            } else {
                MediaOutputOwner.DIPLAY
            }
        } else {
            MediaOutputOwner.OEM_BLUETOOTH
        }
        saveOwner(context, migrated)
        return migrated
    }

    fun saveOwner(context: Context, owner: MediaOutputOwner) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OWNER, owner.name)
                .commit(),
        ) { "Could not persist Geely media owner" }
    }

    private const val LEGACY_PREFERENCES = "xcertplay_airplay"
    private const val LEGACY_OEM_BLUETOOTH_KEY = "oem_bluetooth_music_enabled"
}
