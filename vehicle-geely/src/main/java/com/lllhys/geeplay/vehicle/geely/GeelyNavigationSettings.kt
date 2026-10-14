package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.vehicle.VehicleDisplayCandidate

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
    private const val KEY_DEBUG_DISPLAY_ID = "geely_cluster_debug_display_id"

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

    fun debugDisplayId(context: Context): Int? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_DEBUG_DISPLAY_ID, NO_DISPLAY)
            .takeIf { it != NO_DISPLAY }

    fun saveDebugDisplayId(context: Context, displayId: Int?) {
        require(displayId == null || displayId != Display.DEFAULT_DISPLAY) {
            "The default display cannot be used as a cluster debug target"
        }
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (displayId == null) editor.remove(KEY_DEBUG_DISPLAY_ID) else editor.putInt(KEY_DEBUG_DISPLAY_ID, displayId)
        check(editor.commit()) { "Could not persist Geely cluster debug display" }
    }

    /** Non-default public Presentation displays are the only targets safe enough for explicit debugging. */
    fun debugCandidates(context: Context): List<VehicleDisplayCandidate> =
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .orEmpty()
            .filter { display ->
                display.isValid && display.displayId != Display.DEFAULT_DISPLAY &&
                    display.flags and Display.FLAG_PRESENTATION != 0
            }
            .map { display ->
                val size = Point().also {
                    @Suppress("DEPRECATION")
                    display.getRealSize(it)
                }
                VehicleDisplayCandidate(display.displayId, display.name, size.x, size.y, display.flags)
            }
            .sortedBy(VehicleDisplayCandidate::displayId)

    private const val NO_DISPLAY = -1
}

object GeelyMediaSettings {
    private const val PREFERENCES = "geeplay_geely_media"
    private const val KEY_OWNER = "media_output_owner"
    private const val KEY_BLUETOOTH_AUTO_AVOID = "bluetooth_media_auto_avoid"
    private const val KEY_BLUETOOTH_AUTO_RESTORE = "bluetooth_media_auto_restore"

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

    fun bluetoothAutoAvoid(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH_AUTO_AVOID, false)

    fun saveBluetoothAutoAvoid(context: Context, enabled: Boolean) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BLUETOOTH_AUTO_AVOID, enabled)
                .commit(),
        ) { "Could not persist Geely Bluetooth media arbitration" }
    }

    fun bluetoothAutoRestore(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUETOOTH_AUTO_RESTORE, false)

    fun saveBluetoothAutoRestore(context: Context, enabled: Boolean) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BLUETOOTH_AUTO_RESTORE, enabled)
                .commit(),
        ) { "Could not persist Geely Bluetooth media restore preference" }
    }

    /** The confirmed CarPlay choice defaults to keeping OEM Bluetooth media disabled after CarPlay. */
    fun saveCarPlayOwnerWithAutoAvoid(context: Context) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_OWNER, MediaOutputOwner.DIPLAY.name)
                .putBoolean(KEY_BLUETOOTH_AUTO_AVOID, true)
                .putBoolean(KEY_BLUETOOTH_AUTO_RESTORE, false)
                .commit(),
        ) { "Could not persist Geely CarPlay media arbitration" }
    }

    private const val LEGACY_PREFERENCES = "xcertplay_airplay"
    private const val LEGACY_OEM_BLUETOOTH_KEY = "oem_bluetooth_music_enabled"
}
