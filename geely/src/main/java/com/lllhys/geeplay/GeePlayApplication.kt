package com.lllhys.geeplay

import android.app.Application
import android.content.Context
import com.lllhys.geeplay.vehicle.geely.GeelyVehicleSessionHooks
import com.lllhys.geeplay.vehicle.geely.GeelyBluetoothAudioConflictPolicy
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
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleDataSettings
import com.lllhys.geeplay.vehicle.geely.evcc.EvccConnectionCore
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleControlGateway
import com.lllhys.geeplay.vehicle.geely.evcc.EvccAmbientLightingProvider
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleSystemActionPort
import com.shilapi.xcertplay.media.AmbientLightingProvider
import com.shilapi.xcertplay.vehicle.VehicleSystemActionPort
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataAdapter
import com.shilapi.xcertplay.vehicle.ClusterMapVisibilitySource
import com.shilapi.xcertplay.vehicle.VehicleParkStateSource
import com.shilapi.xcertplay.vehicle.VehicleTyreTelemetrySource
import com.shilapi.xcertplay.vehicle.BluetoothAudioConflictPolicy

class GeePlayApplication : Application(), VehicleIntegrationProvider {
    private lateinit var evccVehicleData: EvccVehicleDataAdapter
    private lateinit var evccConnection: EvccConnectionCore
    private lateinit var evccControl: EvccVehicleControlGateway
    private lateinit var evccAmbientLighting: EvccAmbientLightingProvider
    private lateinit var evccSystemActions: EvccVehicleSystemActionPort

    override fun onCreate() {
        super.onCreate()
        evccConnection = EvccConnectionCore(this)
        evccVehicleData = EvccVehicleDataAdapter(this, evccConnection)
        evccControl = EvccVehicleControlGateway(evccConnection)
        evccAmbientLighting = EvccAmbientLightingProvider(evccControl)
        evccSystemActions = EvccVehicleSystemActionPort(this, evccControl)
        evccVehicleData.prepare()
        GeelyBluetoothAudioConflictPolicy.restoreOnAppOpen(this)
        GeelyNavigationRecovery.restoreOnAppOpen(this)
    }

    override fun createVehicleSessionHooks(context: Context): VehicleSessionHooks =
        GeelyVehicleSessionHooks(
            context.applicationContext,
            onStatus = GeelyNavigationStatusStore::update,
            bluetoothAudioConflict = bluetoothAudioConflictPolicy(context),
            clusterMapVisibility = evccVehicleData.takeIf {
                GeelyClusterDisplaySettings.enabled(context) ||
                    GeelyClusterDisplaySettings.debugDisplayId(context) != null
            },
            instrumentMediaOutput = instrumentMediaOutput(context),
            instrumentCallOutput = instrumentCallOutput(context),
        )

    override fun carPlayVehicleDataAdapter(context: Context): CarPlayVehicleDataAdapter = evccVehicleData

    override fun vehicleParkStateSource(context: Context): VehicleParkStateSource? =
        evccVehicleData.takeIf { EvccVehicleDataSettings.videoWhileParkedEnabled(context) }

    override fun vehicleTyreTelemetrySource(context: Context): VehicleTyreTelemetrySource? =
        evccVehicleData.takeIf { EvccVehicleDataSettings.tyreTelemetryEnabled(context) }

    override fun clusterMapVisibilitySource(context: Context): ClusterMapVisibilitySource = evccVehicleData

    override fun videoInCarEnabled(context: Context): Boolean =
        EvccVehicleDataSettings.videoWhileParkedEnabled(context)

    override fun ambientLightingProvider(context: Context): AmbientLightingProvider = evccAmbientLighting

    override fun vehicleSystemActionPort(context: Context): VehicleSystemActionPort = evccSystemActions

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

    override fun embeddedGeelyWheelKeysAvailable(context: Context): Boolean = false

    override fun legacyPhoneAudioRouteAvailable(context: Context): Boolean = false

    override fun harmanFullscreenAvailable(context: Context): Boolean = false

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
        append('\n')
        append(evccAmbientLighting.diagnostics())
        append('\n')
        append(evccSystemActions.diagnostics())
    }

    internal fun bluetoothAudioConflictPolicy(context: Context): BluetoothAudioConflictPolicy =
        if (GeelyMediaSettings.loadOwner(context) == MediaOutputOwner.DIPLAY &&
            GeelyMediaSettings.bluetoothAutoAvoid(context)
        ) {
            GeelyBluetoothAudioConflictPolicy(context.applicationContext)
        } else {
            BluetoothAudioConflictPolicy.NONE
        }

    override fun onTerminate() {
        if (::evccVehicleData.isInitialized) evccVehicleData.close()
        if (::evccControl.isInitialized) evccControl.close()
        if (::evccConnection.isInitialized) evccConnection.close()
        super.onTerminate()
    }

    private fun geelyClusterDisplayPolicy(context: Context) = GeelyClusterDisplayPolicy(
        enabled = GeelyClusterDisplaySettings.enabled(context),
        debugDisplayId = GeelyClusterDisplaySettings.debugDisplayId(context),
    )
}
