package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.shilapi.xcertplay.transport.EvChargingConnectors

enum class EvccWheelSpeedSampling(val sampleMillis: Long) {
    STANDARD(250L),
    FAST(100L),
}

object EvccVehicleDataSettings {
    private const val PREFERENCES = "geeplay_evcc_vehicle_data"
    private const val KEY_VEHICLE_STATUS_ENABLED = "vehicle_status_enabled"
    private const val KEY_VEHICLE_SPEED_ENABLED = "vehicle_speed_enabled"
    private const val KEY_WHEEL_SPEED_SAMPLING = "wheel_speed_sampling"
    private const val KEY_LOW_CHARGE_PERCENT = "low_charge_percent"
    private const val KEY_CHARGING_CONNECTORS = "charging_connectors"
    private const val KEY_VIDEO_WHILE_PARKED = "video_while_parked"
    private const val KEY_TYRE_TELEMETRY = "tyre_telemetry"

    const val DEFAULT_LOW_CHARGE_PERCENT = 20
    val lowChargePresets = listOf(10, 15, 20, 25, 30)

    fun vehicleStatusEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VEHICLE_STATUS_ENABLED, false)

    fun saveVehicleStatusEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_VEHICLE_STATUS_ENABLED, enabled).commit()) {
            "Could not persist EVCC vehicle-status setting"
        }
    }

    fun vehicleSpeedEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VEHICLE_SPEED_ENABLED, false)

    fun saveVehicleSpeedEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_VEHICLE_SPEED_ENABLED, enabled).commit()) {
            "Could not persist EVCC vehicle-speed setting"
        }
    }

    fun wheelSpeedSampling(context: Context): EvccWheelSpeedSampling =
        preferences(context).getString(KEY_WHEEL_SPEED_SAMPLING, null)
            ?.let { saved -> EvccWheelSpeedSampling.entries.firstOrNull { it.name == saved } }
            ?: EvccWheelSpeedSampling.STANDARD

    fun saveWheelSpeedSampling(context: Context, sampling: EvccWheelSpeedSampling) {
        check(preferences(context).edit().putString(KEY_WHEEL_SPEED_SAMPLING, sampling.name).commit()) {
            "Could not persist EVCC wheel-speed sampling setting"
        }
    }

    fun anyEnabled(context: Context): Boolean =
        vehicleStatusEnabled(context) || vehicleSpeedEnabled(context) ||
            videoWhileParkedEnabled(context) || tyreTelemetryEnabled(context)

    fun videoWhileParkedEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_VIDEO_WHILE_PARKED, false)

    fun saveVideoWhileParkedEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_VIDEO_WHILE_PARKED, enabled).commit()) {
            "Could not persist EVCC parked-video setting"
        }
    }

    fun tyreTelemetryEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_TYRE_TELEMETRY, false)

    fun saveTyreTelemetryEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean(KEY_TYRE_TELEMETRY, enabled).commit()) {
            "Could not persist EVCC tyre-telemetry setting"
        }
    }

    fun lowChargePercent(context: Context): Int =
        preferences(context).getInt(KEY_LOW_CHARGE_PERCENT, DEFAULT_LOW_CHARGE_PERCENT).coerceIn(1, 50)

    fun saveLowChargePercent(context: Context, percent: Int) {
        require(percent in lowChargePresets) { "Unsupported low-charge threshold" }
        check(preferences(context).edit().putInt(KEY_LOW_CHARGE_PERCENT, percent).commit()) {
            "Could not persist EVCC low-charge threshold"
        }
    }

    fun chargingConnectors(context: Context): EvChargingConnectors =
        preferences(context).getString(KEY_CHARGING_CONNECTORS, null)
            ?.let { saved -> EvChargingConnectors.entries.firstOrNull { it.name == saved } }
            ?: EvChargingConnectors.GB_T

    fun saveChargingConnectors(context: Context, connectors: EvChargingConnectors) {
        check(preferences(context).edit().putString(KEY_CHARGING_CONNECTORS, connectors.name).commit()) {
            "Could not persist EVCC charging connectors"
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
