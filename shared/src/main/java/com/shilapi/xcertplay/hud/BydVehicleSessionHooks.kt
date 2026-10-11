package com.shilapi.xcertplay.hud

import android.content.Context
import android.bluetooth.BluetoothAdapter
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSourceKind
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSourceSelector
import com.shilapi.xcertplay.vehicle.SourceCloseReason
import com.shilapi.xcertplay.vehicle.VehicleHostControls
import com.shilapi.xcertplay.vehicle.VehicleSessionHooks

/** Keeps the existing BYD outputs behind the vehicle integration boundary during migration. */
class BydVehicleSessionHooks(context: Context) : VehicleSessionHooks {
    private val appContext = context.applicationContext
    private val sources = RouteGuidanceSourceSelector()
    private var controls: VehicleHostControls? = null
    private var clusterControl: ((Boolean) -> Unit)? = null
    private var activeSession: Any? = null
    private var closed = false

    init {
        BydNavigationOutputs.start(appContext)
    }

    @Synchronized
    override fun attachHostControls(controls: VehicleHostControls) {
        if (closed) return
        this.controls = controls
        val callback: (Boolean) -> Unit = controls::setClusterUiShown
        clusterControl = callback
        BydNavigationOutputs.setClusterStreamControl(callback)
    }

    @Synchronized
    override fun sourceOpened(source: RouteGuidanceSource) {
        if (!closed) sources.opened(source)
    }

    @Synchronized
    override fun sourceReady(source: RouteGuidanceSource) {
        if (closed) return
        val previous = sources.owner
        if (sources.ready(source) && previous != null) {
            BydNavigationOutputs.endNow(
                preserveTurnOverlay = source.kind == RouteGuidanceSourceKind.WIRELESS_RUNTIME_TUNNEL,
            )
        }
    }

    @Synchronized
    override fun onControlFrame(source: RouteGuidanceSource, frame: Iap2Frame) {
        if (!closed && sources.accepts(source)) BydNavigationOutputs.onFrame(frame)
    }

    override fun prepareWirelessBluetooth(adapter: BluetoothAdapter): Boolean =
        BydBluetoothSuspend.resumeAndWait(appContext, adapter)

    override fun onControllerClosing(owner: Any) {
        BydBluetoothSuspend.resume(appContext, owner)
    }

    @Synchronized
    override fun sourceClosed(source: RouteGuidanceSource, reason: SourceCloseReason) {
        if (closed) return
        val wasOwner = sources.owner == source
        sources.closed(source)
        if (wasOwner) {
            BydNavigationOutputs.endNow(
                preserveTurnOverlay = source.kind != RouteGuidanceSourceKind.WIRED &&
                    reason != SourceCloseReason.CONTROLLER_CLOSED,
            )
        }
    }

    @Synchronized
    override fun carPlaySessionStarted(sessionToken: Any, stillActive: () -> Boolean) {
        if (closed) return
        val replacement = activeSession !== sessionToken
        activeSession = sessionToken
        if (replacement) {
            BydNavigationOutputs.start(appContext)
            BydNavigationOutputs.carPlaySessionStarted()
        }
        val preferences = appContext.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (preferences.getBoolean("bt_suspend_during_carplay", false)) {
            BydBluetoothSuspend.suspend(appContext, this, bluetoothSuspendDelayMillis(preferences)) {
                !closed && activeSession === sessionToken && stillActive()
            }
        }
    }

    @Synchronized
    override fun carPlaySessionEnded(sessionToken: Any, replacementExpected: Boolean) {
        if (closed || activeSession !== sessionToken) return
        activeSession = null
        BydNavigationOutputs.endNow(preserveTurnOverlay = replacementExpected)
        BydBluetoothSuspend.resume(appContext, this)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clusterControl?.let(BydNavigationOutputs::clearClusterStreamControl)
        clusterControl = null
        controls = null
        activeSession = null
        sources.reset()
        BydBluetoothSuspend.resume(appContext, this)
        BydNavigationOutputs.endNow()
    }

    private fun bluetoothSuspendDelayMillis(preferences: android.content.SharedPreferences): Long =
        when (preferences.getInt("bt_suspend_delay_seconds", 10)) {
            5 -> 5_000L
            15 -> 15_000L
            30 -> 30_000L
            else -> 10_000L
        }
}
