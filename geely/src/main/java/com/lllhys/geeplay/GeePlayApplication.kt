package com.lllhys.geeplay

import android.app.Application
import android.content.Context
import com.lllhys.geeplay.vehicle.geely.GeelyVehicleSessionHooks
import com.shilapi.xcertplay.vehicle.VehicleIntegrationProvider
import com.shilapi.xcertplay.vehicle.VehicleSessionHooks
import com.shilapi.xcertplay.media.LocalMediaAudioPolicy
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.lllhys.geeplay.vehicle.geely.GeelyMediaSettings
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationStatusStore
import com.lllhys.geeplay.vehicle.geely.GeelyVehicleSettingsController
import com.shilapi.xcertplay.vehicle.VehicleSettingsController
import com.shilapi.xcertplay.vehicle.VehicleClusterDisplayPolicy
import com.lllhys.geeplay.vehicle.geely.GeelyClusterDisplayPolicy
import com.lllhys.geeplay.vehicle.geely.GeelyClusterDisplaySettings
import com.lllhys.geeplay.vehicle.geely.GeelyIntegrationDiagnostics
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationRecovery
import com.lllhys.geeplay.vehicle.geely.GeelyProductSettings
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleDataAdapter
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataAdapter

class GeePlayApplication : Application(), VehicleIntegrationProvider {
    private lateinit var evccVehicleData: EvccVehicleDataAdapter

    override fun onCreate() {
        super.onCreate()
        evccVehicleData = EvccVehicleDataAdapter(this)
        evccVehicleData.prepare()
        GeelyNavigationRecovery.restoreOnAppOpen(this)
    }

    override fun createVehicleSessionHooks(context: Context): VehicleSessionHooks =
        GeelyVehicleSessionHooks(
            context.applicationContext,
            onStatus = GeelyNavigationStatusStore::update,
        )

    override fun carPlayVehicleDataAdapter(context: Context): CarPlayVehicleDataAdapter = evccVehicleData

    override fun mediaAudioPolicy(context: Context): LocalMediaAudioPolicy =
        when (GeelyMediaSettings.loadOwner(context)) {
            MediaOutputOwner.DIPLAY -> LocalMediaAudioPolicy.DIPLAY
            MediaOutputOwner.OEM_BLUETOOTH -> LocalMediaAudioPolicy.OEM_BLUETOOTH
            MediaOutputOwner.PHONE_OR_EXTERNAL -> LocalMediaAudioPolicy.PHONE_OR_EXTERNAL
        }

    override fun productDisplayName(context: Context): String = context.getString(R.string.app_name)

    override fun defaultOemLabel(context: Context): String = "GEELY"

    override fun normalizeSavedOemLabel(context: Context, savedLabel: String, hasCustomIcon: Boolean): String =
        if (savedLabel == "BYD" && !hasCustomIcon) "GEELY" else savedLabel

    override fun defaultCarIconResource(context: Context): Int = R.drawable.ic_geeplay_car_home

    override fun vehicleSettings(context: Context): VehicleSettingsController =
        GeelyVehicleSettingsController()

    override fun legacyBydSettingsAvailable(context: Context): Boolean =
        !GeelyProductSettings.blockLegacyBydMenus(context)

    override fun externalMapEmbeddingAvailable(context: Context): Boolean = false

    override fun diagnosticExportDirectoryName(context: Context): String = "GeePlay"

    override fun clusterDisplayPolicy(context: Context): VehicleClusterDisplayPolicy =
        geelyClusterDisplayPolicy(context)

    override fun requiresClusterStream(context: Context): Boolean =
        geelyClusterDisplayPolicy(context).select(GeelyClusterDisplaySettings.debugCandidates(context)) != null

    override fun updateReleasesUrl(context: Context): String =
        "https://api.github.com/repos/lllhys/GeePlay/releases?per_page=3"

    override fun diagnosticReport(context: Context): String = buildString {
        append(GeelyIntegrationDiagnostics.report(context))
        append('\n')
        append(evccVehicleData.diagnostics())
    }

    override fun onTerminate() {
        if (::evccVehicleData.isInitialized) evccVehicleData.close()
        super.onTerminate()
    }

    private fun geelyClusterDisplayPolicy(context: Context) = GeelyClusterDisplayPolicy(
        enabled = GeelyClusterDisplaySettings.enabled(context),
        debugDisplayId = GeelyClusterDisplaySettings.debugDisplayId(context),
    )
}
