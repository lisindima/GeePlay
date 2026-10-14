package com.lllhys.geeplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccApiInfo
import com.kooo.evcc.evccapi.EvccPageRequest
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSessionInfo
import com.kooo.evcc.evccapi.client.EvccApiClient
import com.lllhys.geeplay.vehicle.geely.GeelyDirectInputServiceAdapter
import com.lllhys.geeplay.vehicle.geely.GeelyDimNavigationAdapter
import com.lllhys.geeplay.vehicle.geely.GeelyInputDiagnostics
import com.lllhys.geeplay.vehicle.geely.GeelyInputEvent
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationBackend
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationConfig
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationDriverState
import com.lllhys.geeplay.vehicle.geely.GeelyNavigationDriverStatus
import com.lllhys.geeplay.vehicle.geely.GeelyVehicleSessionHooks
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceParser
import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneGuidance
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import com.shilapi.xcertplay.navigation.apple.AppleManeuver
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSourceKind
import com.shilapi.xcertplay.vehicle.SourceCloseReason
import com.shilapi.xcertplay.vehicle.VehicleIntegrationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Shell-triggered probes that exist only in the debug APK and require the platform DUMP permission. */
class GeePlayValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { MODE_SNAPSHOT }
        val durationMillis = intent.getLongExtra(EXTRA_DURATION_MILLIS, DEFAULT_DURATION_MILLIS)
            .coerceIn(1_000L, MAX_DURATION_MILLIS)
        Thread({
            val report = runCatching {
                when (mode) {
                    MODE_SNAPSHOT -> snapshotReport()
                    MODE_EVCC -> evccReport()
                    MODE_NAVIGATION -> navigationReport(durationMillis)
                    MODE_LANE -> laneReport(durationMillis)
                    MODE_EAS_LANE -> easLaneReport(
                        durationMillis,
                        allowUnknownVendor = intent.getBooleanExtra(EXTRA_ALLOW_UNKNOWN_VENDOR, false),
                        allowActiveSession = intent.getBooleanExtra(EXTRA_ALLOW_ACTIVE_SESSION, false),
                    )
                    MODE_ROUTE_REPLAY -> routeReplayReport(durationMillis)
                    MODE_ROUTE_RESUBSCRIBE -> routeResubscribeReport()
                    MODE_LOCATION -> mockLocationReport()
                    MODE_LOCATION_CLEANUP -> mockLocationCleanupReport()
                    MODE_INPUT -> inputReport(durationMillis)
                    MODE_DISPLAY -> displayReport(durationMillis, intent.getIntExtra(EXTRA_DISPLAY_ID, -1))
                    else -> error("unknown validation mode: $mode")
                }
            }.fold(
                onSuccess = { it },
                onFailure = { "mode=$mode\nresult=FAILED\nerror=${it.javaClass.name}:${it.message}" },
            )
            writeReport(mode, report)
            runOnUiThread(::finish)
        }, "geeplay-real-car-$mode").apply { isDaemon = true; start() }
    }

    private fun snapshotReport(): String = buildString {
        appendLine(environment("snapshot"))
        appendLine(
            (application as? VehicleIntegrationProvider)?.diagnosticReport(applicationContext)
                ?: "vehicle integration provider unavailable",
        )
    }

    private fun evccReport(): String {
        val ready = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val apiInfoRef = AtomicReference<EvccApiInfo?>()
        val sessionInfoRef = AtomicReference<EvccSessionInfo?>()
        val client = EvccApiClient(applicationContext, listener = object : EvccApiClient.Listener {
            override fun onSessionReady(apiInfo: EvccApiInfo, sessionInfo: EvccSessionInfo) {
                apiInfoRef.set(apiInfo)
                sessionInfoRef.set(sessionInfo)
                ready.countDown()
            }

            override fun onSessionClosed(reason: com.kooo.evcc.evccapi.EvccOperationResult) {
                errors += "sessionClosed=${reason.status}:${reason.message}"
                ready.countDown()
            }

            override fun onError(operation: String, error: Throwable) {
                errors += "$operation=${error.javaClass.simpleName}:${error.message}"
            }
        })
        return try {
            client.connect()
            val connected = ready.await(EVCC_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            buildString {
                appendLine(environment("evcc"))
                appendLine("connected=$connected state=${client.connectionState()}")
                val api = apiInfoRef.get()
                val session = sessionInfoRef.get()
                appendLine(
                    "api=${api?.apiMajor}.${api?.apiMinor} service=${api?.serviceVersionName} " +
                        "code=${api?.serviceVersionCode} contract=${api?.contractHash}",
                )
                appendLine("permissions=${session?.grantedPermissions.orEmpty().sorted()}")
                appendLine("errors=${errors.toList()}")
                if (connected && api != null && session != null) {
                    appendLine("-- capabilities --")
                    client.queryCapabilities().sortedBy { it.id }.forEach {
                        appendLine("${it.id} available=${it.available} permission=${it.requiredPermission} reason=${it.reason}")
                    }
                    appendLine("-- sources --")
                    client.querySourceStates().sortedBy { it.source }.forEach {
                        appendLine("${it.source} state=${it.state} generation=${it.sourceGeneration} message=${it.message}")
                    }
                    appendLine("-- validated selectors --")
                    VALIDATED_SELECTORS.forEach { (label, selector) ->
                        appendLine("$label ${recordLine(runCatching { client.readProperty(selector, true) }.getOrNull())}")
                    }
                    appendLine("-- targeted directory --")
                    DIRECTORY_QUERIES.forEach { query ->
                        appendLine("query=$query")
                        directory(client, query).forEach { appendLine(descriptorLine(it)) }
                    }
                }
            }
        } finally {
            client.close()
        }
    }

    private fun navigationReport(durationMillis: Long): String {
        val statuses = CopyOnWriteArrayList<GeelyNavigationDriverStatus>()
        val config = GeelyNavigationConfig(
            enabled = true,
            backend = GeelyNavigationBackend.AUTO,
            laneGuidanceEnabled = true,
            originalOneOsIcons = false,
            laneNumberBase = 0,
            semanticJunctionEnabled = true,
        )
        val driver = GeelyDimNavigationAdapter(
            applicationContext,
            config,
            onStatus = statuses::add,
            independentLaneRefresh = true,
            navigationDataSource = 9,
        )
        driver.setExecutionGuard { true }
        var started = false
        var published = false
        var cleared = false
        try {
            repeat(20) {
                if (!started) started = driver.start()
                if (started || driver.failed) return@repeat
                Thread.sleep(400L)
            }
            if (started) {
                published = driver.publish(validationSnapshot())
                if (published) Thread.sleep(durationMillis)
            }
            cleared = driver.clear()
            Thread.sleep(500L)
        } finally {
            driver.close()
        }
        return buildString {
            appendLine(environment("navigation"))
            appendLine("started=$started published=$published cleared=$cleared failed=${driver.failed} cleanupPending=${driver.cleanupPending}")
            statuses.forEach { appendLine("status=${it.state} stage=${it.stage} failure=${it.failureType ?: "none"}") }
        }
    }

    private fun laneReport(durationMillis: Long): String {
        val sessionState = carPlaySessionState()
        if (sessionState != "idle") {
            return buildString {
                appendLine(environment(MODE_LANE))
                appendLine(
                    "result=REFUSED session=$sessionState " +
                        "reason=disconnect CarPlay before running the DIM lane probe",
                )
            }
        }

        val statuses = CopyOnWriteArrayList<GeelyNavigationDriverStatus>()
        val config = GeelyNavigationConfig(
            enabled = true,
            backend = GeelyNavigationBackend.AUTO,
            laneGuidanceEnabled = true,
            originalOneOsIcons = false,
            laneNumberBase = 0,
            semanticJunctionEnabled = false,
        )
        val driver = GeelyDimNavigationAdapter(
            applicationContext,
            config,
            onStatus = statuses::add,
            independentLaneRefresh = true,
            laneIconId = { lane ->
                when (lane.position) {
                    0 -> 16 // preferred left
                    1 -> 0 // straight
                    2 -> 4 // straight or right
                    else -> 0
                }
            },
            navigationDataSource = 9,
        )
        driver.setExecutionGuard { carPlaySessionState() == "idle" }
        var started = false
        var published = false
        var cleared = false
        try {
            repeat(20) {
                if (!started) started = driver.start()
                if (started || driver.failed) return@repeat
                Thread.sleep(400L)
            }
            if (started && carPlaySessionState() == "idle") {
                published = driver.publishLanesOnly(laneValidationLanes())
                if (published) Thread.sleep(durationMillis)
            }
            cleared = driver.clearLanesOnly()
            Thread.sleep(500L)
        } finally {
            driver.close()
        }
        return buildString {
            appendLine(environment(MODE_LANE))
            appendLine(
                "pattern=recommended-left,straight,straight-or-right laneBase=0 " +
                    "iconIds=16,0,4 independentRefresh=true dataSource=9 laneOnly=true " +
                    "durationMs=$durationMillis",
            )
            appendLine(
                "started=$started published=$published cleared=$cleared failed=${driver.failed} " +
                    "cleanupPending=${driver.cleanupPending}",
            )
            statuses.forEach { appendLine("status=${it.state} stage=${it.stage} failure=${it.failureType ?: "none"}") }
        }
    }

    private fun easLaneReport(
        durationMillis: Long,
        allowUnknownVendor: Boolean,
        allowActiveSession: Boolean,
    ): String {
        val sessionState = carPlaySessionState()
        if (sessionState != "idle" && !allowActiveSession) {
            return buildString {
                appendLine(environment(MODE_EAS_LANE))
                appendLine("result=REFUSED session=$sessionState reason=disconnect CarPlay before running the EAS lane probe")
            }
        }
        val result = GeelyEasLaneProbe(applicationContext).run(
            lanes = listOf(0 to 16, 1 to 0, 2 to 4),
            durationMillis = durationMillis,
            allowUnknownVendor = allowUnknownVendor,
        )
        return buildString {
            appendLine(environment(MODE_EAS_LANE))
            appendLine(
                "pattern=recommended-left,straight,straight-or-right laneBase=0 iconIds=16,0,4 " +
                    "durationMs=$durationMillis session=$sessionState allowActiveSession=$allowActiveSession",
            )
            append(result)
        }
    }

    private fun carPlaySessionState(): String = runCatching {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val instance = type.getField("INSTANCE").get(null)
        val active = type.getMethod("getActive").invoke(instance) as Boolean
        val retained = type.getMethod("hasSession").invoke(instance) as Boolean
        when {
            active -> "active"
            retained -> "retained"
            else -> "idle"
        }
    }.getOrElse { "unknown" }

    private fun routeResubscribeReport(): String {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val instance = type.getField("INSTANCE").get(null)
        val snapshot = type.getMethod("snapshot").invoke(instance)
            ?: return "${environment(MODE_ROUTE_RESUBSCRIBE)}\nresult=REFUSED reason=no-active-session"
        val controller = snapshot.javaClass.getMethod("getController").invoke(snapshot)
        val sent = controller.javaClass.getMethod("refreshRouteGuidanceUpdates").invoke(controller) as Boolean
        return "${environment(MODE_ROUTE_RESUBSCRIBE)}\nresult=${if (sent) "SENT" else "FAILED"}"
    }

    private fun routeReplayReport(durationMillis: Long): String {
        val sessionState = carPlaySessionState()
        if (sessionState != "idle") {
            return buildString {
                appendLine(environment(MODE_ROUTE_REPLAY))
                appendLine(
                    "result=REFUSED session=$sessionState " +
                        "reason=disconnect CarPlay before replaying synthetic RouteGuidance frames",
                )
            }
        }

        val statuses = CopyOnWriteArrayList<GeelyNavigationDriverStatus>()
        val frameLog = CopyOnWriteArrayList<String>()
        val hooks = GeelyVehicleSessionHooks(
            applicationContext,
            config = GeelyNavigationConfig(
                enabled = true,
                backend = GeelyNavigationBackend.AUTO,
                laneGuidanceEnabled = true,
                originalOneOsIcons = false,
                laneNumberBase = 0,
                semanticJunctionEnabled = false,
            ),
            onStatus = statuses::add,
        )
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRELESS_RUNTIME_TUNNEL)
        val sessionToken = Any()
        val phases = CarPlayRouteGuidanceReplay.phases()
        val phaseMillis = (durationMillis / phases.size).coerceAtLeast(MIN_ROUTE_REPLAY_PHASE_MILLIS)
        var opened = false
        var sessionStarted = false
        var completedPhases = 0
        var replayFailure: Throwable? = null
        try {
            hooks.sourceOpened(source)
            opened = true
            hooks.carPlaySessionStarted(sessionToken) { carPlaySessionState() == "idle" }
            sessionStarted = true
            hooks.sourceReady(source)
            phases.forEach { phase ->
                check(carPlaySessionState() == "idle") { "real CarPlay session became active during replay" }
                val activeBefore = statuses.count { it.state == GeelyNavigationDriverState.ACTIVE }
                phase.frames.forEach { frame ->
                    val summary = AppleRouteGuidanceParser.diagnosticSummary(frame.messageId, frame.payload)
                    frameLog += "phase=${phase.name} $summary"
                    Log.i(ROUTE_REPLAY_TAG, "phase=${phase.name} $summary")
                    hooks.onControlFrame(source, frame)
                }
                check(awaitRouteReplayPublish(statuses, activeBefore)) {
                    "DIM output did not publish phase ${phase.name} within ${ROUTE_REPLAY_PUBLISH_TIMEOUT_MILLIS}ms"
                }
                completedPhases += 1
                Thread.sleep(phaseMillis)
            }
            hooks.onControlFrame(source, CarPlayRouteGuidanceReplay.routeEnded())
            Thread.sleep(ROUTE_REPLAY_CLEANUP_MILLIS)
        } catch (error: Throwable) {
            replayFailure = error
        } finally {
            if (sessionStarted) hooks.carPlaySessionEnded(sessionToken, replacementExpected = false)
            if (opened) hooks.sourceClosed(source, SourceCloseReason.CHANNEL_CLOSED)
            hooks.close()
            Thread.sleep(ROUTE_REPLAY_CLEANUP_MILLIS)
        }
        return buildString {
            appendLine(environment(MODE_ROUTE_REPLAY))
            appendLine(
                "result=${if (replayFailure == null && completedPhases == phases.size) "COMPLETE" else "FAILED"} " +
                    "session=$sessionState phases=$completedPhases/${phases.size} phaseMs=$phaseMillis",
            )
            phases.forEach { phase ->
                appendLine("expected phase=${phase.name} oemLaneIconIds=${phase.expectedOemLaneIconIds}")
            }
            frameLog.forEach(::appendLine)
            statuses.forEach { appendLine("status=${it.state} stage=${it.stage} failure=${it.failureType ?: "none"}") }
            replayFailure?.let { appendLine("error=${it.javaClass.name}:${it.message}") }
        }
    }

    private fun awaitRouteReplayPublish(
        statuses: List<GeelyNavigationDriverStatus>,
        activeBefore: Int,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + ROUTE_REPLAY_PUBLISH_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (statuses.count { it.state == GeelyNavigationDriverState.ACTIVE } > activeBefore) {
                return true
            }
            if (statuses.any {
                    it.state == GeelyNavigationDriverState.FAILED ||
                        it.state == GeelyNavigationDriverState.CLEANUP_PENDING
                }
            ) {
                return false
            }
            Thread.sleep(ROUTE_REPLAY_PUBLISH_POLL_MILLIS)
        }
        return false
    }

    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant")
    private fun mockLocationReport(): String {
        check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "ACCESS_FINE_LOCATION is not granted"
        }
        val manager = getSystemService(LocationManager::class.java)
        val start = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: error("GPS has no last-known location")
        val bearing = intent.getFloatExtra(EXTRA_BEARING_DEGREES, 270f).let { ((it % 360f) + 360f) % 360f }
        val stepMeters = intent.getFloatExtra(EXTRA_STEP_METERS, 8f).coerceIn(1f, 30f)
        val steps = intent.getIntExtra(EXTRA_STEPS, 12).coerceIn(1, 120)
        val intervalMillis = intent.getLongExtra(EXTRA_INTERVAL_MILLIS, 1_000L).coerceIn(250L, 5_000L)
        val speed = stepMeters * 1_000f / intervalMillis
        var latitude = start.latitude
        var longitude = start.longitude
        var emitted = 0
        var cleanup = false
        try {
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,
                true,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val radians = Math.toRadians(bearing.toDouble())
            repeat(steps) { index ->
                latitude += Math.cos(radians) * stepMeters / METERS_PER_LATITUDE_DEGREE
                longitude += Math.sin(radians) * stepMeters /
                    (METERS_PER_LATITUDE_DEGREE * Math.cos(Math.toRadians(latitude)))
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    accuracy = 1f
                    this.bearing = bearing
                    this.speed = speed
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
                emitted += 1
                if (index == 0 || (index + 1) % 5 == 0) {
                    Log.i(LOCATION_TAG, "mock GPS emitted=${index + 1}/$steps bearing=$bearing speed=$speed")
                }
                Thread.sleep(intervalMillis)
            }
        } finally {
            cleanup = runCatching {
                manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                manager.removeTestProvider(LocationManager.GPS_PROVIDER)
                true
            }.getOrDefault(false)
            Log.i(LOCATION_TAG, "mock GPS complete emitted=$emitted cleanup=$cleanup")
        }
        return buildString {
            appendLine(environment(MODE_LOCATION))
            appendLine(
                "emitted=$emitted steps=$steps stepMeters=$stepMeters bearing=$bearing " +
                    "intervalMs=$intervalMillis cleanup=$cleanup",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun mockLocationCleanupReport(): String {
        val manager = getSystemService(LocationManager::class.java)
        val removed = runCatching {
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        }.getOrDefault(false)
        Log.i(LOCATION_TAG, "forced mock GPS cleanup removed=$removed")
        return "${environment(MODE_LOCATION_CLEANUP)}\nremoved=$removed"
    }

    private fun inputReport(durationMillis: Long): String {
        val statuses = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<GeelyInputEvent>()
        val adapter = GeelyDirectInputServiceAdapter(
            applicationContext,
            onEvent = { event -> events += event; true },
            onStatus = statuses::add,
        )
        adapter.start()
        Thread.sleep(durationMillis)
        adapter.close()
        Thread.sleep(1_000L)
        return buildString {
            appendLine(environment("input"))
            appendLine("serviceAvailable=${GeelyDirectInputServiceAdapter.available(applicationContext)}")
            appendLine("diagnostics=${GeelyInputDiagnostics.snapshot()}")
            statuses.forEach { appendLine("status=$it") }
            events.forEach { appendLine("event raw=${it.rawCode} gesture=${it.gesture} source=${it.source}") }
        }
    }

    private fun displayReport(durationMillis: Long, requestedDisplayId: Int): String {
        val manager = getSystemService(DisplayManager::class.java)
        val displays = manager?.displays.orEmpty()
        val target = displays.firstOrNull { it.displayId == requestedDisplayId }
            ?: manager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        var shown = false
        var dismissed = false
        var failure: String? = null
        var presentation: Presentation? = null
        if (target != null) {
            val ready = CountDownLatch(1)
            runOnUiThread {
                runCatching {
                    presentation = object : Presentation(this, target) {}
                    presentation!!.setContentView(TextView(this).apply {
                        text = "GeePlay\nreal-car display validation\n${target.name} #${target.displayId}"
                        textSize = 42f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.rgb(120, 0, 0))
                    })
                    presentation!!.show()
                    shown = true
                }.onFailure { failure = "${it.javaClass.simpleName}:${it.message}" }
                ready.countDown()
            }
            ready.await(5, TimeUnit.SECONDS)
            if (shown) Thread.sleep(durationMillis)
            val closed = CountDownLatch(1)
            runOnUiThread {
                runCatching { presentation?.dismiss(); dismissed = true }
                    .onFailure { failure = "${it.javaClass.simpleName}:${it.message}" }
                closed.countDown()
            }
            closed.await(5, TimeUnit.SECONDS)
        }
        return buildString {
            appendLine(environment("display"))
            displays.sortedBy { it.displayId }.forEach {
                appendLine("display id=${it.displayId} name=${it.name} valid=${it.isValid} flags=0x${it.flags.toString(16)}")
            }
            appendLine("target=${target?.displayId} shown=$shown dismissed=$dismissed failure=${failure ?: "none"}")
        }
    }

    private fun directory(client: EvccApiClient, query: String): List<EvccPropertyDescriptor> {
        val descriptors = LinkedHashMap<String, EvccPropertyDescriptor>()
        var pageToken = ""
        var snapshotToken = ""
        var pages = 0
        do {
            val page = client.queryPropertyDirectory(
                EvccPageRequest(
                    requestId = "geeplay-real-car-$query-$pages",
                    pageToken = pageToken,
                    query = query,
                    limit = EvccApiContract.MAX_PAGE_SIZE,
                    snapshotToken = snapshotToken,
                ),
            )
            if (page.status !in SUCCESS_STATUSES) break
            if (snapshotToken.isEmpty()) snapshotToken = page.snapshotToken
            page.items.mapNotNull(EvccPropertyRecord::descriptor).forEach {
                descriptors["${it.source}:${it.canonicalId}:${it.areaId}"] = it
            }
            pageToken = page.nextPageToken
            pages += 1
        } while (pageToken.isNotEmpty() && pages < 10)
        return descriptors.values.sortedWith(compareBy({ it.source }, { it.canonicalId }, { it.areaId }))
    }

    private fun recordLine(record: EvccPropertyRecord?): String {
        if (record == null) return "exception"
        val descriptor = record.descriptor
        val value = record.value
        return "status=${record.status} resolved=${record.resolvedSource} " +
            "descriptor=${descriptor?.source}:${descriptor?.canonicalId}:${descriptor?.areaId}:${descriptor?.name}:" +
            "${descriptor?.dataType}:${descriptor?.access} valueType=${value?.type} value=${value?.displayValue} " +
            "initialized=${value?.initialized} valueStatus=${value?.status} observedAt=${value?.observedAt}"
    }

    private fun descriptorLine(value: EvccPropertyDescriptor): String =
        "descriptor source=${value.source} id=${value.canonicalId} hex=${value.hexId} name=${value.name} " +
            "group=${value.group} areaType=${value.areaType} areaId=${value.areaId} areaName=${value.areaName} " +
            "type=${value.dataType} access=${value.access} change=${value.changeMode} generation=${value.sourceGeneration}"

    private fun validationSnapshot(): AppleNavigationSnapshot = AppleNavigationSnapshot(
        generation = 1,
        revision = 1,
        routeState = AppleRouteState.ROUTE_SET,
        currentRoad = "Validation Road",
        maneuver = AppleManeuver(
            index = 1,
            type = 2,
            drivingSide = 0,
            afterRoad = "GeePlay Validation",
            linkedLaneGuidanceIndex = 7,
            junction = null,
        ),
        nextDistanceMeters = 320,
        remainingMeters = 4_200,
        remainingSeconds = 600,
        arrivalEpochSeconds = System.currentTimeMillis() / 1_000L + 600,
        laneGuidance = AppleLaneGuidance(
            eventIndex = 7,
            description = "validation lanes",
            lanes = listOf(
                AppleLane(0, AppleLaneStatus.GOOD, listOf(-45), null),
                AppleLane(1, AppleLaneStatus.PREFERRED, listOf(0), 0),
                AppleLane(2, AppleLaneStatus.NOT_GOOD, listOf(45), null),
            ),
        ),
        updatedAtNanos = SystemClock.elapsedRealtimeNanos(),
    )

    private fun laneValidationLanes(): List<AppleLane> = listOf(
        AppleLane(0, AppleLaneStatus.PREFERRED, listOf(-45), -45),
        AppleLane(1, AppleLaneStatus.GOOD, listOf(0, 45), null),
        AppleLane(2, AppleLaneStatus.GOOD, listOf(180), null),
    )

    @Suppress("DEPRECATION")
    private fun environment(mode: String): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return "mode=$mode model=${android.os.Build.MODEL} fingerprint=${android.os.Build.FINGERPRINT} " +
            "version=${packageInfo.versionName} code=${packageInfo.longVersionCode} at=${System.currentTimeMillis()}"
    }

    private fun writeReport(mode: String, report: String) {
        val root = File(getExternalFilesDir(null), "validation").apply { mkdirs() }
        File(root, "latest-$mode.txt").writeText(report)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_DURATION_MILLIS = "durationMillis"
        const val EXTRA_ALLOW_UNKNOWN_VENDOR = "allowUnknownVendor"
        const val EXTRA_ALLOW_ACTIVE_SESSION = "allowActiveSession"
        const val EXTRA_DISPLAY_ID = "displayId"
        const val EXTRA_BEARING_DEGREES = "bearingDegrees"
        const val EXTRA_STEP_METERS = "stepMeters"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_INTERVAL_MILLIS = "intervalMillis"
        const val MODE_SNAPSHOT = "snapshot"
        const val MODE_EVCC = "evcc"
        const val MODE_NAVIGATION = "navigation"
        const val MODE_LANE = "lane"
        const val MODE_EAS_LANE = "eas-lane"
        const val MODE_ROUTE_REPLAY = "route-replay"
        const val MODE_ROUTE_RESUBSCRIBE = "route-resubscribe"
        const val MODE_LOCATION = "location"
        const val MODE_LOCATION_CLEANUP = "location-cleanup"
        const val MODE_INPUT = "input"
        const val MODE_DISPLAY = "display"
        const val DEFAULT_DURATION_MILLIS = 8_000L
        const val MAX_DURATION_MILLIS = 60_000L
        const val EVCC_CONNECT_TIMEOUT_SECONDS = 15L
        const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        const val LOCATION_TAG = "GeePlayLocationProbe"
        const val ROUTE_REPLAY_TAG = "GeePlayRouteReplay"
        const val MIN_ROUTE_REPLAY_PHASE_MILLIS = 1_500L
        const val ROUTE_REPLAY_PUBLISH_TIMEOUT_MILLIS = 10_000L
        const val ROUTE_REPLAY_PUBLISH_POLL_MILLIS = 100L
        const val ROUTE_REPLAY_CLEANUP_MILLIS = 750L
        val SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
        val DIRECTORY_QUERIES = listOf(
            "TIRE", "TYRE", "TPMS", "NAVI_MODE", "MAP_VISIBLE", "AMBIENT", "LIGHT",
        )
        val VALIDATED_SELECTORS = listOf(
            "BATTERY_SOC" to selector(1_051_136L, EvccApiContract.Source.ADAPT_API),
            "RANGE_TOTAL_KM" to selector(1_050_624L, EvccApiContract.Source.ADAPT_API),
            "RANGE_GASOLINE_KM" to selector(1_054_720L, EvccApiContract.Source.ADAPT_API),
            "RANGE_EV_KM" to selector(1_054_976L, EvccApiContract.Source.ADAPT_API),
            "CHARGING" to selector(605_225_728L, EvccApiContract.Source.ADAPT_API),
            "VEHICLE_SPEED" to selector(1_048_832L, EvccApiContract.Source.ADAPT_API),
            "VEHICLE_SPEED_IPK" to selector(1_055_232L, EvccApiContract.Source.ADAPT_API),
            "GEAR" to selector(289_408_000L, EvccApiContract.Source.CAPI),
            "TYRE_PRESSURE_FL" to selector(5_243_136L, EvccApiContract.Source.ADAPT_API),
            "TYRE_PRESSURE_FR" to selector(5_243_392L, EvccApiContract.Source.ADAPT_API),
            "TYRE_PRESSURE_RL" to selector(5_243_648L, EvccApiContract.Source.ADAPT_API),
            "TYRE_PRESSURE_RR" to selector(5_243_904L, EvccApiContract.Source.ADAPT_API),
            "TYRE_TEMPERATURE_FL" to selector(5_244_160L, EvccApiContract.Source.ADAPT_API),
            "TYRE_TEMPERATURE_FR" to selector(5_244_416L, EvccApiContract.Source.ADAPT_API),
            "TYRE_TEMPERATURE_RL" to selector(5_244_672L, EvccApiContract.Source.ADAPT_API),
            "TYRE_TEMPERATURE_RR" to selector(5_244_928L, EvccApiContract.Source.ADAPT_API),
            "AMBIENCE_MAIN_COLOR" to selector(537_526_784L, EvccApiContract.Source.ADAPT_API),
            "AMBIENCE_COLOR_ALL" to selector(704_905_472L, EvccApiContract.Source.ADAPT_API),
            "AMBIENCE_INTENSITY_ALL" to selector(704_905_728L, EvccApiContract.Source.ADAPT_API),
            "AMBIENCE_COLOR_ZONE_8" to selector(704_905_472L, EvccApiContract.Source.ADAPT_API, 8),
            "AMBIENCE_INTENSITY_ZONE_8" to selector(704_905_728L, EvccApiContract.Source.ADAPT_API, 8),
            "AMBIENCE_COLOR_ZONE_128" to selector(704_905_472L, EvccApiContract.Source.ADAPT_API, 128),
            "AMBIENCE_INTENSITY_ZONE_128" to selector(704_905_728L, EvccApiContract.Source.ADAPT_API, 128),
            "AMBIENCE_COLOR_ZONE_2048" to selector(704_905_472L, EvccApiContract.Source.ADAPT_API, 2_048),
            "AMBIENCE_INTENSITY_ZONE_2048" to selector(704_905_728L, EvccApiContract.Source.ADAPT_API, 2_048),
            "AMBIENCE_COLOR_ZONE_32768" to selector(704_905_472L, EvccApiContract.Source.ADAPT_API, 32_768),
            "AMBIENCE_INTENSITY_ZONE_32768" to selector(704_905_728L, EvccApiContract.Source.ADAPT_API, 32_768),
        )

        private fun selector(id: Long, source: String, areaId: Int = 0) = EvccPropertySelector(
            id = id,
            source = source,
            areaId = areaId,
            resolutionMode = EvccApiContract.ResolutionMode.STRICT_UNIQUE,
        )

    }
}
