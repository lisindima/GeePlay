package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataAdapter
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataSession

/** Keeps the existing BYD ADB vehicle-data path behind the product-neutral vehicle-data seam. */
class BydVehicleDataAdapter(context: Context) : CarPlayVehicleDataAdapter {
    private val appContext = context.applicationContext

    override fun prepare() {
        if (BydOutputSettings.batteryToIphoneActive(appContext)) {
            BydBatteryStatus.start(appContext)
        }
    }

    override fun createSession(): CarPlayVehicleDataSession = CarPlayVehicleDataSession(
        statusProvider = if (BydOutputSettings.batteryToIphoneActive(appContext)) {
            BydNavigationOutputs.batteryStatus(appContext)
        } else {
            null
        },
        speedSource = if (BydOutputSettings.wheelSpeedToIphoneActive(appContext)) {
            BydNavigationOutputs.wheelSpeed(appContext)
        } else {
            null
        },
        chargingConnectors = BydOutputSettings.chargingConnectors(appContext),
    )

    override fun diagnostics(): String =
        "BYD vehicle data: battery=${BydOutputSettings.batteryToIphoneActive(appContext)}, " +
            "wheelSpeed=${BydOutputSettings.wheelSpeedToIphoneActive(appContext)}"
}
