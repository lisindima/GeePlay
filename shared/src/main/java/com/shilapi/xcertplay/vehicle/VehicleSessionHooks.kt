package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.LocalMediaAudioPolicy
import com.shilapi.xcertplay.media.AmbientLightingProvider
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import java.io.Closeable
import android.content.Context
import android.bluetooth.BluetoothAdapter

enum class SourceCloseReason {
    CHANNEL_CLOSED,
    CONNECTION_RESTARTED,
    CONTROLLER_CLOSED,
}

/** Commands a vehicle integration may send back to the active CarPlay host. */
interface VehicleHostControls {
    fun setClusterUiShown(shown: Boolean)
    fun sendMediaButton(index: Int): Boolean
    fun requestSiri(): Boolean
    fun isCommunicationActive(): Boolean
    fun answerCall(): Boolean = false
    fun endCall(): Boolean = false
    fun zoomDashboardMap(zoomIn: Boolean): Boolean = false
    fun sendKnob(wheel: Int = 0, select: Boolean = false, back: Boolean = false): Boolean = false
}

/**
 * Narrow lifecycle boundary between CarPlay transport orchestration and vehicle integrations.
 * Implementations must return quickly from callbacks and move vendor I/O to their own workers.
 */
interface VehicleSessionHooks : Closeable {
    fun attachHostControls(controls: VehicleHostControls) = Unit
    fun sourceOpened(source: RouteGuidanceSource) = Unit
    fun sourceReady(source: RouteGuidanceSource) = Unit
    fun onControlFrame(source: RouteGuidanceSource, frame: Iap2Frame) = Unit
    fun prepareWirelessBluetooth(adapter: BluetoothAdapter): Boolean = true
    fun onControllerClosing(owner: Any) = Unit
    fun sourceClosed(source: RouteGuidanceSource, reason: SourceCloseReason) = Unit
    fun carPlaySessionStarted(sessionToken: Any, stillActive: () -> Boolean = { true }) = Unit
    fun carPlaySessionEnded(sessionToken: Any, replacementExpected: Boolean) = Unit
    override fun close() = Unit

    companion object {
        val NONE: VehicleSessionHooks = object : VehicleSessionHooks {}
    }
}

/** Application-level composition point for product-specific vehicle integrations. */
interface VehicleIntegrationProvider {
    fun createVehicleSessionHooks(context: Context): VehicleSessionHooks
    fun carPlayVehicleDataAdapter(context: Context): CarPlayVehicleDataAdapter? = null
    fun vehicleParkStateSource(context: Context): VehicleParkStateSource? = null
    fun vehicleTyreTelemetrySource(context: Context): VehicleTyreTelemetrySource? = null
    fun clusterMapVisibilitySource(context: Context): ClusterMapVisibilitySource? = null
    /** null keeps the upstream product's own parked-video setting. */
    fun videoInCarEnabled(context: Context): Boolean? = null
    fun instrumentMediaOutput(context: Context): InstrumentMediaOutput = InstrumentMediaOutput.NONE
    fun instrumentCallOutput(context: Context): InstrumentCallOutput = InstrumentCallOutput.NONE
    fun ambientLightingProvider(context: Context): AmbientLightingProvider? = null
    fun vehicleSystemActionPort(context: Context): VehicleSystemActionPort? = null
    fun mediaAudioPolicy(context: Context): LocalMediaAudioPolicy = LocalMediaAudioPolicy.DIPLAY
    fun vehicleSettings(context: Context): VehicleSettingsController? = null
    /** Whether this product should render DiPlay's legacy BYD settings and availability copy. */
    fun legacyBydSettingsAvailable(context: Context): Boolean = true
    /** Whether this product exposes DiPlay's launcher-facing map embedding feature. */
    fun externalMapEmbeddingAvailable(context: Context): Boolean = true
    /** Visible subdirectory below Downloads used for exported diagnostic reports. */
    fun diagnosticExportDirectoryName(context: Context): String = "DiPlay"
    fun clusterDisplayPolicy(context: Context): VehicleClusterDisplayPolicy? = null
    fun requiresClusterStream(context: Context): Boolean = false
    fun productDisplayName(context: Context): String = "DiPlay"
    fun defaultOemLabel(context: Context): String = "BYD"
    fun normalizeSavedOemLabel(context: Context, savedLabel: String, hasCustomIcon: Boolean): String = savedLabel
    fun defaultCarIconResource(context: Context): Int? = null
    fun updateReleasesUrl(context: Context): String? = null
    fun diagnosticReport(context: Context): String? = null
}

class CompositeVehicleSessionHooks(hooks: Iterable<VehicleSessionHooks>) : VehicleSessionHooks {
    private val delegates = hooks.toList()

    override fun attachHostControls(controls: VehicleHostControls) =
        delegates.forEachSafely { it.attachHostControls(controls) }

    override fun sourceOpened(source: RouteGuidanceSource) =
        delegates.forEachSafely { it.sourceOpened(source) }

    override fun sourceReady(source: RouteGuidanceSource) =
        delegates.forEachSafely { it.sourceReady(source) }

    override fun onControlFrame(source: RouteGuidanceSource, frame: Iap2Frame) =
        delegates.forEachSafely { it.onControlFrame(source, frame) }

    override fun prepareWirelessBluetooth(adapter: BluetoothAdapter): Boolean {
        var ready = true
        delegates.forEach { hook ->
            if (!runCatching { hook.prepareWirelessBluetooth(adapter) }.getOrDefault(false)) ready = false
        }
        return ready
    }

    override fun onControllerClosing(owner: Any) =
        delegates.forEachSafely { it.onControllerClosing(owner) }

    override fun sourceClosed(source: RouteGuidanceSource, reason: SourceCloseReason) =
        delegates.forEachSafely { it.sourceClosed(source, reason) }

    override fun carPlaySessionStarted(sessionToken: Any, stillActive: () -> Boolean) =
        delegates.forEachSafely { it.carPlaySessionStarted(sessionToken, stillActive) }

    override fun carPlaySessionEnded(sessionToken: Any, replacementExpected: Boolean) =
        delegates.forEachSafely { it.carPlaySessionEnded(sessionToken, replacementExpected) }

    override fun close() = delegates.asReversed().forEachSafely(VehicleSessionHooks::close)

    private inline fun Iterable<VehicleSessionHooks>.forEachSafely(action: (VehicleSessionHooks) -> Unit) {
        forEach { runCatching { action(it) } }
    }
}
