package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import com.shilapi.xcertplay.navigation.runtime.NavigationOutput
import com.shilapi.xcertplay.vehicle.VehicleMapMirror

/** Separate endpoints prevent map and manoeuvre clients from competing for the same window. */
data class GeelyQnxLayout(
    val endpoint: GeelyQnxWindowConfig,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val display: Int = 2,
    val layer: Int = 6,
) {
    init {
        GeelyQnxProtocol.hello(x, y, width, height)
        require(display in 1..5 && layer in 0..8)
    }
}

object GeelyQnxSettings {
    private const val PREFS = "geeplay_qnx_windows"
    fun load(context: Context, map: Boolean): GeelyQnxLayout? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = if (map) "map" else "turns"
        if (!p.getBoolean("${key}_enabled", false)) return null
        return runCatching {
            GeelyQnxLayout(
                GeelyQnxWindowConfig(p.getString("${key}_host", p.getString("host", ""))!!,
                    p.getInt("${key}_port", if (map) 53281 else 53283), true),
                p.getInt("${key}_x", 0), p.getInt("${key}_y", 0),
                p.getInt("${key}_width", 320), p.getInt("${key}_height", 160),
                p.getInt("${key}_display", 2), p.getInt("${key}_layer", 6),
            )
        }.getOrNull()
    }

    fun save(context: Context, map: Boolean, layout: GeelyQnxLayout?) {
        val key = if (map) "map" else "turns"
        require(layout == null || layout.endpoint.enabled) { "A window needs an enabled endpoint" }
        val other = load(context, !map)
        require(layout == null || other == null ||
            layout.endpoint.host != other.endpoint.host || layout.endpoint.port != other.endpoint.port) {
            "Map and manoeuvre windows need separate endpoints"
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        p.putBoolean("${key}_enabled", layout != null)
        layout?.let {
            p.putString("${key}_host", it.endpoint.host).putInt("${key}_port", it.endpoint.port)
                .putInt("${key}_x", it.x).putInt("${key}_y", it.y)
                .putInt("${key}_width", it.width).putInt("${key}_height", it.height)
                .putInt("${key}_display", it.display).putInt("${key}_layer", it.layer)
        }
        p.apply()
        com.shilapi.xcertplay.vehicle.VehicleSettingsChanges.requestReconnect()
    }

    fun mapMirror(context: Context, streamWidth: Int, streamHeight: Int): VehicleMapMirror? =
        load(context, true)?.let {
            runCatching { GeelyQnxMapSurface(it.endpoint, it.x, it.y, it.width, it.height, streamWidth, streamHeight) }
                .onFailure { error -> GeelyQnxStatus.update("map", "initialization_failed:${error.javaClass.simpleName}") }
                .getOrNull()
        }

    fun turnOutput(context: Context): List<NavigationOutput> = load(context, false)?.let {
        listOf(GeelyQnxTurnOutput(it.endpoint, it.x, it.y, it.width, it.height))
    } ?: emptyList()
}
