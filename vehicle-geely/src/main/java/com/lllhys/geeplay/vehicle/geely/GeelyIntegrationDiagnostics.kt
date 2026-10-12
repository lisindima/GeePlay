package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.vehicle.VehicleDisplayCandidate
import java.util.concurrent.atomic.AtomicLong

object GeelyIntegrationDiagnostics {
    private val routeUpdates = AtomicLong()
    private val maneuverUpdates = AtomicLong()
    private val laneUpdates = AtomicLong()
    private val rejectedFrames = AtomicLong()
    @Volatile private var activeSource = "none"
    @Volatile private var sessionGeneration = 0L
    @Volatile private var sessionActive = false
    @Volatile private var inputStatus = "not started"
    @Volatile private var bluetoothAudioStatus = "not started"
    @Volatile private var lastFrameAtMillis = 0L
    @Volatile private var lastPublishAtMillis = 0L
    @Volatile private var visibleManeuverCount = 0
    @Volatile private var visibleLaneCount = 0
    @Volatile private var lastOemMethod = "none"
    @Volatile private var lastOemResult = "not_called"

    fun sourceReady(source: RouteGuidanceSource) {
        activeSource = "${source.kind.name}:${source.id}"
    }

    fun sourceClosed(source: RouteGuidanceSource) {
        if (activeSource.endsWith(":${source.id}")) activeSource = "none"
    }

    fun sessionStarted() {
        sessionGeneration += 1
        sessionActive = true
    }

    fun sessionEnded() {
        sessionGeneration += 1
        sessionActive = false
    }

    fun frame(messageId: Int, accepted: Boolean) {
        lastFrameAtMillis = SystemClock.elapsedRealtime()
        if (!accepted) rejectedFrames.incrementAndGet()
        when (messageId) {
            0x5201 -> routeUpdates.incrementAndGet()
            0x5202 -> maneuverUpdates.incrementAndGet()
            0x5204 -> laneUpdates.incrementAndGet()
        }
    }

    fun publication(publication: NavigationPublication) {
        activeSource = "${publication.source.kind.name}:${publication.source.id}"
        sessionGeneration = publication.sessionGeneration
        lastPublishAtMillis = SystemClock.elapsedRealtime()
        visibleManeuverCount = 1
        visibleLaneCount = publication.snapshot.laneGuidance?.lanes?.size ?: 0
    }

    fun cleared(clear: NavigationClear) {
        sessionGeneration = clear.sessionGeneration
        visibleManeuverCount = 0
        visibleLaneCount = 0
    }

    fun oemCall(method: String, result: String) {
        lastOemMethod = method.take(80)
        lastOemResult = result.take(80)
    }

    fun input(message: String) {
        inputStatus = message.take(160)
    }

    fun bluetoothAudio(message: String) {
        bluetoothAudioStatus = message.take(160)
    }

    fun report(context: Context): String = buildString {
        val navigation = GeelyNavigationSettings.load(context)
        val driver = GeelyNavigationStatusStore.snapshot()
        val recovery = GeelyNavigationRecoveryJournal(context).load()
        val navigationSdk = runCatching {
            Class.forName(GeelyDimNavigationAdapter.DIM_INTERACTION, false, context.classLoader)
            Class.forName(GeelyDimNavigationAdapter.NAVIGATION_INFORMATION, false, context.classLoader)
        }.isSuccess
        val permission = context.checkSelfPermission("com.permission.adnaviservice") == PackageManager.PERMISSION_GRANTED
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("geelyProduct package=${context.packageName} version=${packageInfo?.versionName ?: "unknown"}")
        appendLine("geelyNavigation enabled=${navigation.enabled} backend=${navigation.backend} lanes=${navigation.laneGuidanceEnabled}")
        appendLine("geelyCapability sdk=$navigationSdk adnaviservicePermission=$permission")
        appendLine("geelyEvidence sdk=${if (navigationSdk) "AVAILABLE" else "UNAVAILABLE"} vehicleNavigation=NOT_CONFIRMED lane=NOT_CONFIRMED wheel=NOT_CONFIRMED clusterDisplay=NOT_CONFIRMED")
        appendLine("geelyDriver state=${driver.state} stage=${driver.stage} failure=${driver.failureType ?: "none"} cleanupPending=${recovery != null}")
        appendLine("geelyOemCall method=$lastOemMethod result=$lastOemResult")
        appendLine("geelyRoute source=$activeSource sessionGeneration=$sessionGeneration sessionActive=$sessionActive frames5201=${routeUpdates.get()} frames5202=${maneuverUpdates.get()} frames5204=${laneUpdates.get()} rejected=${rejectedFrames.get()} lastFrameElapsedMs=$lastFrameAtMillis lastPublishElapsedMs=$lastPublishAtMillis maneuvers=$visibleManeuverCount lanes=$visibleLaneCount")
        appendLine("geelyMedia owner=${GeelyMediaSettings.loadOwner(context)} retainBluetooth=${GeelyMediaSettings.loadOwner(context) == com.shilapi.xcertplay.media.MediaOutputOwner.OEM_BLUETOOTH} autoAvoid=${GeelyMediaSettings.bluetoothAutoAvoid(context)} arbitration=$bluetoothAudioStatus")
        val input = GeelyInputDiagnostics.snapshot()
        appendLine("geelyInput profile=${GeelyInputSettings.loadProfile(context)} status=$inputStatus bind=${input.bindAttempts} register=${input.registrations} unregister=${input.unregisterAttempts} death=${input.binderDeaths} quarantine=${input.quarantines} active=${input.registered}")
        val displays = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        val debugDisplayId = GeelyClusterDisplaySettings.debugDisplayId(context)
        val displayPolicy = GeelyClusterDisplayPolicy(
            enabled = GeelyClusterDisplaySettings.enabled(context),
            debugDisplayId = debugDisplayId,
        )
        val candidates = displays.map { display ->
            val size = android.graphics.Point().also {
                @Suppress("DEPRECATION")
                display.getRealSize(it)
            }
            VehicleDisplayCandidate(display.displayId, display.name, size.x, size.y, display.flags)
        }
        val selectedDisplay = displayPolicy.select(GeelyClusterDisplaySettings.debugCandidates(context))
        val selectionReason = when {
            debugDisplayId != null && selectedDisplay != null -> "explicit_debug_override"
            debugDisplayId != null -> "debug_display_missing_or_unsafe"
            !GeelyClusterDisplaySettings.enabled(context) -> "disabled"
            !displayPolicy.hasValidatedFirmware() -> "no_validated_firmware"
            selectedDisplay == null -> "display_mismatch_or_ambiguous"
            else -> "validated_profile_match"
        }
        appendLine(
            "geelyDisplays fingerprint=${Build.FINGERPRINT} " +
                "enabled=${GeelyClusterDisplaySettings.enabled(context)} " +
                "debugDisplayId=${debugDisplayId ?: "none"} " +
                "validatedFirmware=${GeelyClusterDisplayPolicy().hasValidatedFirmware()} " +
                "validatedProfiles=${GeelyClusterDisplayPolicy.VALIDATED_PROFILES.size} " +
                "selectedProfile=${selectedDisplay?.profileId ?: "none"} reason=$selectionReason " +
                "surfaceCreation=not_attempted owner=not_exposed_by_public_api",
        )
        candidates.forEachIndexed { index, display ->
            appendLine("geelyDisplay index=$index id=${display.displayId} name=${display.name} size=${display.width}x${display.height} flags=${display.flags}")
        }
    }
}
