package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.shilapi.xcertplay.transport.EvChargingConnectors

object EvccVehicleDataSettings {
    private const val PREFERENCES = "geeplay_evcc_vehicle_data"
    private const val KEY_VEHICLE_STATUS_ENABLED = "vehicle_status_enabled"
    private const val KEY_VEHICLE_SPEED_ENABLED = "vehicle_speed_enabled"
    private const val KEY_LOW_CHARGE_PERCENT = "low_charge_percent"
    private const val KEY_CHARGING_CONNECTORS = "charging_connectors"

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

    fun anyEnabled(context: Context): Boolean =
        vehicleStatusEnabled(context) || vehicleSpeedEnabled(context)

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
