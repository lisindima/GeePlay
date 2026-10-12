package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceCoordinator
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationOutput
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceParser
import com.shilapi.xcertplay.navigation.runtime.CarPlayNavigationOverlay
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.vehicle.SourceCloseReason
import com.shilapi.xcertplay.vehicle.BluetoothAudioConflictPolicy
import com.shilapi.xcertplay.vehicle.ClusterMapVisibilitySource
import com.shilapi.xcertplay.vehicle.VehicleHostControls
import com.shilapi.xcertplay.vehicle.InstrumentCallOutput
import com.shilapi.xcertplay.vehicle.InstrumentCallSnapshot
import com.shilapi.xcertplay.vehicle.InstrumentMediaOutput
import com.shilapi.xcertplay.vehicle.InstrumentNowPlayingSnapshot
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.vehicle.VehicleSessionHooks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class GeelyTurnOverlayOutput(
    private val semanticJunctionEnabled: Boolean,
    private val delegate: NavigationOutput = CarPlayNavigationOverlay,
) : NavigationOutput {
    override fun publish(publication: NavigationPublication) {
        val filtered = if (semanticJunctionEnabled) publication else publication.copy(
            snapshot = publication.snapshot.copy(
                maneuver = publication.snapshot.maneuver.copy(junction = null),
            ),
        )
        delegate.publish(filtered)
    }

    override fun clear(clear: NavigationClear) = delegate.clear(clear)
    override fun close() = delegate.close()
}

class GeelyVehicleSessionHooks(
    context: Context,
    config: GeelyNavigationConfig = GeelyNavigationSettings.load(context),
    onStatus: (GeelyNavigationDriverStatus) -> Unit = {},
    additionalOutputs: List<NavigationOutput> = emptyList(),
    private val bluetoothAudioConflict: BluetoothAudioConflictPolicy = BluetoothAudioConflictPolicy.NONE,
    private val clusterMapVisibility: ClusterMapVisibilitySource? = null,
    private val instrumentMediaOutput: InstrumentMediaOutput = InstrumentMediaOutput.NONE,
    private val instrumentCallOutput: InstrumentCallOutput = InstrumentCallOutput.NONE,
) : VehicleSessionHooks {
    private val output = GeelyNavigationOutput(context.applicationContext, config, onStatus)
    private val turnOverlay = GeelyTurnOverlayOutput(config.semanticJunctionEnabled)
    private val diagnosticsOutput = object : NavigationOutput {
        override fun publish(publication: NavigationPublication) =
            GeelyIntegrationDiagnostics.publication(publication)

        override fun clear(clear: NavigationClear) = GeelyIntegrationDiagnostics.cleared(clear)
    }
    private val coordinator = RouteGuidanceCoordinator(
        listOf(diagnosticsOutput, output, turnOverlay) + additionalOutputs,
    )
    private val input = GeelyInputRuntime(context.applicationContext, GeelyIntegrationDiagnostics::input)
    private val closed = AtomicBoolean()
    private var activeSession: Any? = null
    @Volatile private var hostControls: VehicleHostControls? = null
    private var lastClusterMapVisible: Boolean? = null
    private val mediaState = ClusterSongState()
    private val callState = CarPlayCallState()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "geeplay-route-expiry").apply { isDaemon = true }
    }

    init {
        timer.scheduleWithFixedDelay({
            coordinator.refresh()
            clusterMapVisibility?.mapShownOnCluster()?.let { shown ->
                if (lastClusterMapVisible != shown) {
                    lastClusterMapVisible = shown
                    hostControls?.setClusterUiShown(shown)
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    override fun sourceOpened(source: RouteGuidanceSource) {
        if (!closed.get()) coordinator.sourceOpened(source)
    }
    override fun attachHostControls(controls: VehicleHostControls) {
        if (!closed.get()) {
            hostControls = controls
            input.attachHostControls(controls)
        }
    }
    override fun sourceReady(source: RouteGuidanceSource) {
        if (closed.get()) return
        GeelyIntegrationDiagnostics.sourceReady(source)
        coordinator.sourceReady(source)
    }
    override fun onControlFrame(source: RouteGuidanceSource, frame: Iap2Frame) {
        if (closed.get()) return
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            mediaState.accept(frame)?.let { song ->
                instrumentMediaOutput.publish(
                    InstrumentNowPlayingSnapshot(song.line, playing = song.playing),
                )
            } ?: run {
                if (mediaState.current() == null) instrumentMediaOutput.clear()
            }
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE && callState.accept(frame)) {
            callState.current()?.let { call ->
                instrumentCallOutput.publish(
                    InstrumentCallSnapshot(
                        displayName = call.name,
                        phase = when (call.phase) {
                            CarPlayCallCard.Phase.RINGING -> InstrumentCallSnapshot.Phase.RINGING
                            CarPlayCallCard.Phase.DIALING -> InstrumentCallSnapshot.Phase.DIALING
                            CarPlayCallCard.Phase.ACTIVE -> InstrumentCallSnapshot.Phase.ACTIVE
                        },
                    ),
                )
            } ?: instrumentCallOutput.clear()
        }
        if (frame.messageId == AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE ||
            frame.messageId == AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION
        ) {
            GeelyIntegrationDiagnostics.frame(
                frame.messageId,
                AppleRouteGuidanceParser.isValidFrame(frame.messageId, frame.payload),
            )
        }
        coordinator.onFrame(source, frame)
    }
    override fun sourceClosed(source: RouteGuidanceSource, reason: SourceCloseReason) {
        if (closed.get()) return
        GeelyIntegrationDiagnostics.sourceClosed(source)
        coordinator.sourceClosed(source)
    }
    override fun carPlaySessionStarted(sessionToken: Any, stillActive: () -> Boolean) {
        if (closed.get() || activeSession === sessionToken) return
        activeSession = sessionToken
        GeelyIntegrationDiagnostics.sessionStarted()
        coordinator.sessionStarted(sessionToken)
        input.sessionStarted(sessionToken)
        bluetoothAudioConflict.acquire(sessionToken) {
            !closed.get() && activeSession === sessionToken && stillActive()
        }
    }
    override fun carPlaySessionEnded(sessionToken: Any, replacementExpected: Boolean) {
        if (closed.get() || activeSession !== sessionToken) return
        activeSession = null
        hostControls = null
        GeelyIntegrationDiagnostics.sessionEnded()
        mediaState.clear()
        callState.clear()
        instrumentMediaOutput.clear()
        instrumentCallOutput.clear()
        bluetoothAudioConflict.release(sessionToken)
        input.sessionEnded(sessionToken)
        coordinator.sessionEnded(sessionToken, replacementExpected)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession = null
        timer.shutdownNow()
        bluetoothAudioConflict.close()
        instrumentMediaOutput.close()
        instrumentCallOutput.close()
        input.close()
        coordinator.close()
    }
}
