package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.lllhys.geeplay.vehicle.geely.GeelyClusterDisplaySettings
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccApiInfo
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPageRequest
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSessionInfo
import com.kooo.evcc.evccapi.EvccSourceState
import com.kooo.evcc.evccapi.client.EvccApiClient
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataAdapter
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataSession
import com.shilapi.xcertplay.vehicle.ClusterMapVisibilitySource
import com.shilapi.xcertplay.vehicle.VehicleDataDiagnostics
import com.shilapi.xcertplay.vehicle.VehicleParkStateSource
import com.shilapi.xcertplay.vehicle.VehicleTyreTelemetrySnapshot
import com.shilapi.xcertplay.vehicle.VehicleTyreTelemetrySource
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class EvccVehicleDataState {
    DISABLED,
    CONNECTING,
    AUTHORIZATION_REQUIRED,
    INCOMPATIBLE,
    DISCOVERING,
    READY,
    DEGRADED,
    UNAVAILABLE,
    CLOSED,
}

/**
 * Geely adapter for EVCC's public read-only Binder interface.
 *
 * All Binder work stays on [worker]. iAP2 providers only read [telemetry]'s synchronized cache.
 * Remote subscriptions are the union of settings pre-warm and immutable active-session demand.
 */
class EvccVehicleDataAdapter internal constructor(
    context: Context,
    clientFactory: (Context, EvccApiClient.Listener) -> EvccClientPort,
) : CarPlayVehicleDataAdapter, VehicleParkStateSource, VehicleTyreTelemetrySource, ClusterMapVisibilitySource {
    constructor(context: Context) : this(context, ::OfficialEvccClientPort)

    constructor(context: Context, core: EvccConnectionCore) : this(
        context,
        { _, listener -> core.readPort(listener) },
    )

    private val telemetry = EvccVehicleTelemetryStore()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "geeplay-evcc-vehicle").apply { isDaemon = true }
    }
    private val appContext = context.applicationContext
    private val demandRegistry = EvccDemandRegistry()
    private val requested = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private val stateLock = Any()
    private val refreshLock = Any()
    private val activeSessionOwners = LinkedHashSet<Any>()
    private val activeSpeedSources = LinkedHashSet<EvccVehicleSpeedSource>()
    private val pendingRefreshSignals = LinkedHashSet<EvccLogicalSignal>()
    private var refreshWorkerScheduled = false
    private var demandedSignals = emptySet<EvccLogicalSignal>()
    private var resolved = emptyList<EvccResolvedProperty>()
    private var sourceStates = emptyMap<String, EvccSourceState>()
    private var grantedPermissions = emptySet<String>()
    private var serviceVersion = "-"
    private var protocolVersion = "-"
    private var detail = "disabled"
    private var motionEventCount = 0L
    private var motionRefreshRequestCount = 0L
    private var motionRefreshReadCount = 0L
    private var lastMotionEvent = "-"
    private val propertyTraceStates = LinkedHashMap<EvccLogicalSignal, PropertyTraceState>()
    private lateinit var client: EvccClientPort

    @Volatile
    private var state = EvccVehicleDataState.DISABLED

    private val listener = object : EvccApiClient.Listener {
        override fun onConnectionStateChanged(state: EvccApiClient.ConnectionState, detail: String) {
            if (closed.get()) return
            when (state) {
                EvccApiClient.ConnectionState.BINDING ->
                    updateState(EvccVehicleDataState.CONNECTING, detail)

                EvccApiClient.ConnectionState.WAITING_TO_RETRY -> {
                    retireRemoteData()
                    updateState(EvccVehicleDataState.CONNECTING, detail)
                }

                EvccApiClient.ConnectionState.DISCONNECTED -> {
                    retireRemoteData()
                    if (requested.get() && this@EvccVehicleDataAdapter.state !in TERMINAL_STATES) {
                        updateState(EvccVehicleDataState.UNAVAILABLE, detail)
                    }
                }

                EvccApiClient.ConnectionState.CONNECTED -> Unit
            }
        }

        override fun onSessionReady(apiInfo: EvccApiInfo, sessionInfo: EvccSessionInfo) {
            val currentGeneration = generation.incrementAndGet()
            synchronized(stateLock) {
                serviceVersion = apiInfo.serviceVersionName.ifBlank { apiInfo.serviceVersionCode.toString() }
                protocolVersion = "${apiInfo.apiMajor}.${apiInfo.apiMinor}"
                grantedPermissions = sessionInfo.grantedPermissions.toSet()
                resolved = emptyList()
                sourceStates = emptyMap()
                motionEventCount = 0L
                motionRefreshRequestCount = 0L
                motionRefreshReadCount = 0L
                lastMotionEvent = "-"
                propertyTraceStates.clear()
            }
            telemetry.clear()
            diagnose(
                "evcc-session",
                "ready service=$serviceVersion api=$protocolVersion permissions=${grantedPermissions.sorted()}",
            )
            if (apiInfo.apiMajor != REQUIRED_API_MAJOR || apiInfo.apiMinor < REQUIRED_API_MINOR) {
                updateState(EvccVehicleDataState.INCOMPATIBLE, "requires EvccApi 1.2")
                client.disconnect()
                return
            }
            val missing = REQUIRED_PERMISSIONS - sessionInfo.grantedPermissions.toSet()
            if (missing.isNotEmpty()) {
                updateState(
                    EvccVehicleDataState.AUTHORIZATION_REQUIRED,
                    "missing ${missing.sorted().joinToString()}",
                )
                client.disconnect()
                return
            }
            updateState(EvccVehicleDataState.DISCOVERING, "resolving Geely properties")
            executeWorker { initializeSession(currentGeneration) }
        }

        override fun onPropertyEvent(record: EvccPropertyRecord, sequence: Long, eventType: String) {
            executeWorker {
                synchronized(stateLock) { resolved }.firstOrNull { it.matches(record) }?.let { binding ->
                    if (binding.signal in MOTION_SIGNALS) {
                        synchronized(stateLock) {
                            motionEventCount += 1
                            lastMotionEvent = "${binding.signal}:$eventType#$sequence"
                        }
                    }
                    acceptTelemetry(
                        binding,
                        record,
                        EvccObservationOrigin.PROPERTY_EVENT,
                        "eventType=$eventType eventSequence=$sequence",
                    )
                    refreshReadyState()
                }
            }
        }

        override fun onSourceStateChanged(state: EvccSourceState, sequence: Long) {
            executeWorker {
                val changed = synchronized(stateLock) {
                    val previous = sourceStates[state.source]
                    sourceStates = sourceStates + (state.source to state)
                    previous == null || previous.state != state.state ||
                        previous.sourceGeneration != state.sourceGeneration
                }
                telemetry.sourceState(state.source, state.sourceGeneration, state.state)
                invalidateSpeedSamples()
                if (changed && requested.get() && !closed.get()) {
                    val currentGeneration = generation.incrementAndGet()
                    updateState(
                        EvccVehicleDataState.DISCOVERING,
                        "source ${state.source}=${state.state} generation=${state.sourceGeneration}",
                    )
                    initializeSession(currentGeneration)
                } else {
                    refreshReadyState()
                }
            }
        }

        override fun onResyncRequired(expectedSequence: Long, actualSequence: Long) {
            val currentGeneration = generation.incrementAndGet()
            telemetry.clear()
            invalidateSpeedSamples()
            updateState(EvccVehicleDataState.DISCOVERING, "resync $expectedSequence->$actualSequence")
            // The official 1.2 client also replays the committed replace-all subscription. A fresh
            // discovery here covers a gap that raced the initial resolve and keeps demand changes.
            executeWorker { initializeSession(currentGeneration) }
        }

        override fun onSessionClosed(reason: EvccOperationResult) {
            retireRemoteData()
            updateState(
                if (reason.status == EvccApiContract.Status.PERMISSION_DENIED) {
                    EvccVehicleDataState.AUTHORIZATION_REQUIRED
                } else {
                    EvccVehicleDataState.UNAVAILABLE
                },
                reason.status,
            )
        }

        override fun onError(operation: String, error: Throwable) {
            updateState(state, "$operation:${error.javaClass.simpleName}")
        }
    }

    init {
        VehicleDataDiagnostics.initialize(appContext)
        client = clientFactory(appContext, listener)
    }

    override fun prepare() {
        if (closed.get()) return
        demandRegistry.replace(PREWARM_OWNER, preferenceSignals())
        reconcileDemand()
    }

    override fun parked(maxAgeMillis: Long): Boolean? {
        requestRefresh(setOf(EvccLogicalSignal.GEAR))
        return telemetry.parked(maxAgeMillis)
    }

    override fun start() {
        if (closed.get() || !EvccVehicleDataSettings.tyreTelemetryEnabled(appContext)) return
        demandRegistry.replace(TYRE_OWNER, TYRE_SIGNALS)
        reconcileDemand()
    }

    override fun stop() {
        demandRegistry.release(TYRE_OWNER)
        reconcileDemand()
    }

    override fun snapshot(maxAgeMillis: Long): VehicleTyreTelemetrySnapshot? {
        requestRefresh(TYRE_SIGNALS)
        return telemetry.tyreTelemetry(maxAgeMillis)
    }

    override fun mapShownOnCluster(): Boolean? {
        requestRefresh(setOf(EvccLogicalSignal.CLUSTER_MAP_VISIBLE))
        return telemetry.clusterMapVisible(CLUSTER_VISIBILITY_MAX_AGE_MILLIS)
    }

    override fun createSession(): CarPlayVehicleDataSession {
        if (closed.get()) return CarPlayVehicleDataSession()
        val lowChargePercent = EvccVehicleDataSettings.lowChargePercent(appContext)
        val statusReady = EvccVehicleDataSettings.vehicleStatusEnabled(appContext) &&
            telemetry.vehicleStatus(lowChargePercent) != null
        val motionReady = EvccVehicleDataSettings.vehicleSpeedEnabled(appContext) &&
            telemetry.motion(maxAgeMillis = Long.MAX_VALUE) != null
        val statusProvider = if (statusReady) {
            VehicleStatusProvider {
                requestRefresh(BATTERY_SIGNALS)
                telemetry.vehicleStatus(lowChargePercent)
            }
        } else {
            null
        }
        val speedSource = if (motionReady) {
            EvccVehicleSpeedSource(
                telemetry,
                requestRefresh = ::requestMotionRefresh,
                onDiagnostic = { message -> Log.i(TAG, message) },
                sampleMillis = EvccVehicleDataSettings.wheelSpeedSampling(appContext).sampleMillis,
            )
        } else {
            null
        }
        diagnose(
            "carplay-session",
            "created statusProvider=${statusProvider != null} speedSource=${speedSource != null} " +
                "statusEnabled=${EvccVehicleDataSettings.vehicleStatusEnabled(appContext)} " +
                "speedEnabled=${EvccVehicleDataSettings.vehicleSpeedEnabled(appContext)}",
        )
        val sessionSignals = buildSet {
            if (statusProvider != null) addAll(BATTERY_SIGNALS)
            if (speedSource != null) addAll(MOTION_SIGNALS)
        }
        if (sessionSignals.isEmpty()) {
            return CarPlayVehicleDataSession(
                chargingConnectors = EvccVehicleDataSettings.chargingConnectors(appContext),
            )
        }
        val owner = Any()
        synchronized(stateLock) {
            activeSessionOwners += owner
            speedSource?.let(activeSpeedSources::add)
        }
        demandRegistry.replace(owner, sessionSignals)
        reconcileDemand()
        return CarPlayVehicleDataSession(
            statusProvider = statusProvider,
            speedSource = speedSource,
            chargingConnectors = EvccVehicleDataSettings.chargingConnectors(appContext),
            release = {
                speedSource?.stop()
                synchronized(stateLock) {
                    activeSessionOwners -= owner
                    speedSource?.let(activeSpeedSources::remove)
                }
                demandRegistry.release(owner)
                reconcileDemand()
            },
        )
    }

    fun openEvccSettings(): Boolean = runCatching {
        appContext.startActivity(
            Intent(EvccApiContract.ACTION_OPEN_SETTINGS)
                .setPackage(EvccApiContract.EVCC_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        updateState(EvccVehicleDataState.UNAVAILABLE, "settings:${it.javaClass.simpleName}")
        false
    }

    override fun diagnostics(): String {
        val demand = demandRegistry.current()
        val ready = telemetry.readySignals().map { it.name }.sorted()
        val snapshot = synchronized(stateLock) {
            val sources = sourceStates.values.sortedBy { it.source }
                .joinToString(prefix = "[", postfix = "]") {
                    "${it.source}:${it.state}@${it.sourceGeneration}"
                }
            val speedSources = activeSpeedSources.joinToString(prefix = "[", postfix = "]") {
                it.diagnostics()
            }
            "state=$state service=$serviceVersion " +
                "api=$protocolVersion contract=${EvccApiContract.CONTRACT_HASH} " +
                "permissions=${grantedPermissions.sorted()} demand=${demand.map { it.name }.sorted()} " +
                "sources=$sources resolved=${resolved.map { "${it.signal.name}:${it.selector.id}" }.sorted()} " +
                "sessions=${activeSessionOwners.size} motionEvents=$motionEventCount " +
                "motionRefreshRequests=$motionRefreshRequestCount motionRefreshReads=$motionRefreshReadCount " +
                "lastMotionEvent=$lastMotionEvent speedSources=$speedSources detail=$detail"
        }
        return buildString {
            appendLine("EVCC vehicle data: installed=${evccInstalled()} $snapshot ready=$ready")
            telemetry.propertyDiagnostics().forEach { appendLine("EVCC $it") }
        }.trimEnd()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        demandRegistry.clear()
        generation.incrementAndGet()
        val speedSources = synchronized(stateLock) {
            activeSpeedSources.toList().also {
                activeSessionOwners.clear()
                activeSpeedSources.clear()
                resolved = emptyList()
                sourceStates = emptyMap()
            }
        }
        speedSources.forEach(EvccVehicleSpeedSource::stop)
        synchronized(refreshLock) {
            pendingRefreshSignals.clear()
            refreshWorkerScheduled = false
        }
        telemetry.clear()
        client.close()
        worker.shutdownNow()
        updateState(EvccVehicleDataState.CLOSED, "closed")
    }

    private fun reconcileDemand() {
        if (closed.get()) return
        val nextDemand = demandRegistry.current()
        val changed = synchronized(stateLock) {
            (demandedSignals != nextDemand).also { demandedSignals = nextDemand }
        }
        if (nextDemand.isEmpty()) {
            requested.set(false)
            if (changed) retireRemoteData()
            client.disconnect()
            updateState(EvccVehicleDataState.DISABLED, "disabled")
            return
        }
        if (!evccInstalled()) {
            requested.set(false)
            retireRemoteData()
            client.disconnect()
            updateState(EvccVehicleDataState.UNAVAILABLE, "EVCC not installed")
            return
        }
        requested.set(true)
        if (changed && client.connectionState() == EvccApiClient.ConnectionState.CONNECTED) {
            val currentGeneration = generation.incrementAndGet()
            updateState(EvccVehicleDataState.DISCOVERING, "reconciling vehicle-data demand")
            executeWorker { initializeSession(currentGeneration) }
        } else if (client.connectionState() != EvccApiClient.ConnectionState.CONNECTED) {
            updateState(EvccVehicleDataState.CONNECTING, "connecting")
        }
        client.connect()
    }

    private fun initializeSession(expectedGeneration: Long) {
        val demand = demandedSignals(expectedGeneration) ?: return
        try {
            val capabilities = client.queryCapabilities().associateBy { it.id }
            val unavailable = REQUIRED_PERMISSIONS.filter { capabilities[it]?.available != true }
            if (unavailable.isNotEmpty()) {
                updateState(
                    EvccVehicleDataState.AUTHORIZATION_REQUIRED,
                    "unavailable ${unavailable.sorted().joinToString()}",
                )
                client.disconnect()
                return
            }
            val dynamic = discoverDynamicProperties(demand)
            val latestSourceStates = client.querySourceStates().associateBy(EvccSourceState::source)
            if (!current(expectedGeneration)) return
            synchronized(stateLock) { sourceStates = latestSourceStates }
            latestSourceStates.values.forEach { source ->
                telemetry.sourceState(source.source, source.sourceGeneration, source.state)
            }
            val availableSources = latestSourceStates.values
                .filter { it.state == EvccApiContract.Status.AVAILABLE }
                .associateBy(EvccSourceState::source)
            val next = EvccGeelyPropertyProfile.bindings
                .filter { it.signal in demand }
                .mapNotNull { resolve(it, availableSources) }
                .plus(dynamic.filter { it.signal in demand })
                .distinctBy(EvccResolvedProperty::signal)
            if (!current(expectedGeneration)) return
            val subscription = client.replaceSubscriptions(next.map(EvccResolvedProperty::selector))
            if (!subscription.isSuccess) {
                updateState(EvccVehicleDataState.DEGRADED, "subscription:${subscription.status}")
                return
            }
            if (!current(expectedGeneration)) return
            synchronized(stateLock) { resolved = next }
            next.forEach { binding ->
                diagnose(
                    "evcc-resolve",
                    "signal=${binding.signal} source=${binding.descriptor.source} " +
                        "id=${binding.descriptor.canonicalId} area=${binding.descriptor.areaId} " +
                        "generation=${binding.descriptor.sourceGeneration} type=${binding.descriptor.dataType} " +
                        "scale=${binding.candidate.scale} offset=${binding.candidate.offset}",
                )
            }
            telemetry.retainSignals(next.mapTo(linkedSetOf(), EvccResolvedProperty::signal))
            for (binding in next) {
                if (!current(expectedGeneration)) return
                val record = client.readProperty(binding.selector, forceRefresh = false)
                if (!current(expectedGeneration)) return
                acceptTelemetry(
                    binding,
                    record,
                    EvccObservationOrigin.INITIAL_READ,
                    "forceRefresh=false",
                )
            }
            if (availableSources.isEmpty()) {
                updateState(EvccVehicleDataState.DEGRADED, "vehicle property sources unavailable")
            } else {
                refreshReadyState()
            }
        } catch (error: Throwable) {
            if (current(expectedGeneration)) {
                updateState(EvccVehicleDataState.UNAVAILABLE, "initialize:${error.javaClass.simpleName}")
            }
        }
    }

    private fun requestMotionRefresh() {
        synchronized(stateLock) { motionRefreshRequestCount += 1 }
        requestRefresh(MOTION_SIGNALS)
    }

    private fun discoverDynamicProperties(demand: Set<EvccLogicalSignal>): List<EvccResolvedProperty> {
        if (demand.none { it in DYNAMIC_SIGNALS }) return emptyList()
        val descriptors = LinkedHashMap<String, EvccPropertyDescriptor>()
        DIRECTORY_QUERIES.forEach { query ->
            var pageToken = ""
            var snapshotToken = ""
            var pages = 0
            do {
                val page = client.queryPropertyDirectory(
                    EvccPageRequest(
                        requestId = "geeplay-${query.lowercase(Locale.ROOT)}-$pages",
                        pageToken = pageToken,
                        query = query,
                        limit = EvccApiContract.MAX_PAGE_SIZE,
                        snapshotToken = snapshotToken,
                    ),
                )
                if (page.status !in SUCCESS_STATUSES) break
                if (snapshotToken.isEmpty()) snapshotToken = page.snapshotToken
                page.items.mapNotNull(EvccPropertyRecord::descriptor).forEach { descriptor ->
                    descriptors["${descriptor.source}:${descriptor.canonicalId}:${descriptor.areaId}"] = descriptor
                }
                pageToken = page.nextPageToken
                pages += 1
            } while (pageToken.isNotEmpty() && pages < MAX_DIRECTORY_PAGES)
        }
        return EvccDynamicPropertyDiscovery.resolve(descriptors.values)
    }

    private fun requestRefresh(signals: Set<EvccLogicalSignal>) {
        if (!requested.get() || closed.get()) return
        val schedule = synchronized(refreshLock) {
            pendingRefreshSignals += signals
            if (refreshWorkerScheduled) false else {
                refreshWorkerScheduled = true
                true
            }
        }
        if (schedule && !executeWorker(::drainRefreshRequests)) {
            synchronized(refreshLock) { refreshWorkerScheduled = false }
        }
    }

    private fun drainRefreshRequests() {
        while (requested.get() && !closed.get()) {
            val signals = synchronized(refreshLock) {
                if (pendingRefreshSignals.isEmpty()) {
                    refreshWorkerScheduled = false
                    return
                }
                pendingRefreshSignals.toSet().also { pendingRefreshSignals.clear() }
            }
            val expectedGeneration = generation.get()
            try {
                val currentBindings = synchronized(stateLock) {
                    resolved.filter { it.signal in signals }
                }
                for (binding in currentBindings) {
                    if (!current(expectedGeneration)) break
                    if (binding.signal in MOTION_SIGNALS) {
                        synchronized(stateLock) { motionRefreshReadCount += 1 }
                    }
                    val record = client.readProperty(binding.selector, forceRefresh = false)
                    if (!current(expectedGeneration)) break
                    acceptTelemetry(
                        binding,
                        record,
                        EvccObservationOrigin.REFRESH_READ,
                        "forceRefresh=false",
                    )
                }
                if (current(expectedGeneration)) refreshReadyState()
            } catch (error: Throwable) {
                if (!closed.get()) updateState(state, "refresh:${error.javaClass.simpleName}")
            }
        }
        synchronized(refreshLock) { refreshWorkerScheduled = false }
    }

    private fun resolve(
        binding: EvccPropertyBinding,
        availableSources: Map<String, EvccSourceState>,
    ): EvccResolvedProperty? {
        for (candidate in binding.candidates) {
            val sourceState = availableSources[candidate.source] ?: continue
            val requested = EvccPropertySelector(
                id = candidate.id,
                source = candidate.source,
                areaId = candidate.areaId,
                resolutionMode = EvccApiContract.ResolutionMode.STRICT_UNIQUE,
            )
            val record = runCatching { client.resolveProperty(requested) }.getOrNull() ?: continue
            val descriptor = record.descriptor ?: continue
            if (record.status !in SUCCESS_STATUSES || !descriptor.readable() ||
                !descriptor.supports(binding.signal) ||
                descriptor.source != candidate.source || descriptor.canonicalId != candidate.id ||
                descriptor.areaId != candidate.areaId ||
                (sourceState.sourceGeneration != 0L && descriptor.sourceGeneration != 0L &&
                    sourceState.sourceGeneration != descriptor.sourceGeneration)
            ) {
                continue
            }
            return EvccResolvedProperty(
                signal = binding.signal,
                candidate = candidate,
                selector = EvccPropertySelector(
                    id = descriptor.canonicalId,
                    source = descriptor.source,
                    areaId = descriptor.areaId,
                    resolutionMode = EvccApiContract.ResolutionMode.STRICT_UNIQUE,
                ),
                descriptor = descriptor,
            )
        }
        return null
    }

    private fun refreshReadyState() {
        val demand = demandRegistry.current()
        val batteryDemanded = demand.any { it in BATTERY_SIGNALS }
        val speedDemanded = EvccLogicalSignal.VEHICLE_SPEED in demand
        val gearDemanded = EvccLogicalSignal.GEAR in demand
        val tyreDemanded = demand.any { it in TYRE_SIGNALS }
        val clusterVisibilityDemanded = EvccLogicalSignal.CLUSTER_MAP_VISIBLE in demand
        val batteryReady = telemetry.vehicleStatus(EvccVehicleDataSettings.lowChargePercent(appContext)) != null
        val readySignals = telemetry.readySignals()
        val speedReady = EvccLogicalSignal.VEHICLE_SPEED in readySignals
        val gearReady = EvccLogicalSignal.GEAR in readySignals
        val tyreReady = telemetry.tyreTelemetry(Long.MAX_VALUE) != null
        val clusterVisibilityReady = telemetry.clusterMapVisible(Long.MAX_VALUE) != null
        val complete = (!batteryDemanded || batteryReady) &&
            (!speedDemanded || speedReady) &&
            (!gearDemanded || gearReady) &&
            (!tyreDemanded || tyreReady) &&
            (!clusterVisibilityDemanded || clusterVisibilityReady)
        updateState(
            if (complete && demand.isNotEmpty()) EvccVehicleDataState.READY else EvccVehicleDataState.DEGRADED,
            "battery=$batteryReady speed=$speedReady gear=$gearReady tyre=$tyreReady " +
                "clusterVisibility=$clusterVisibilityReady",
        )
    }

    private fun retireRemoteData() {
        generation.incrementAndGet()
        telemetry.clear()
        invalidateSpeedSamples()
        synchronized(stateLock) {
            resolved = emptyList()
            sourceStates = emptyMap()
            grantedPermissions = emptySet()
            propertyTraceStates.clear()
        }
    }

    private fun demandedSignals(expectedGeneration: Long): Set<EvccLogicalSignal>? =
        synchronized(stateLock) { demandedSignals.takeIf { current(expectedGeneration) } }

    private fun preferenceSignals(): Set<EvccLogicalSignal> = buildSet {
        if (EvccVehicleDataSettings.vehicleStatusEnabled(appContext)) addAll(BATTERY_SIGNALS)
        if (EvccVehicleDataSettings.vehicleSpeedEnabled(appContext)) addAll(MOTION_SIGNALS)
        if (EvccVehicleDataSettings.videoWhileParkedEnabled(appContext)) add(EvccLogicalSignal.GEAR)
        if (GeelyClusterDisplaySettings.enabled(appContext) ||
            GeelyClusterDisplaySettings.debugDisplayId(appContext) != null
        ) add(EvccLogicalSignal.CLUSTER_MAP_VISIBLE)
    }

    private fun current(expectedGeneration: Long): Boolean =
        requested.get() && !closed.get() && generation.get() == expectedGeneration

    private fun invalidateSpeedSamples() {
        synchronized(stateLock) { activeSpeedSources.toList() }.forEach(EvccVehicleSpeedSource::invalidate)
    }

    @Suppress("DEPRECATION")
    private fun evccInstalled(): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(EvccApiContract.EVCC_PACKAGE, 0)
    }.isSuccess

    private fun executeWorker(action: () -> Unit): Boolean = try {
        worker.execute(action)
        true
    } catch (_: RejectedExecutionException) {
        if (!closed.get()) updateState(EvccVehicleDataState.UNAVAILABLE, "worker unavailable")
        false
    }

    private fun updateState(next: EvccVehicleDataState, nextDetail: String) {
        val changed = synchronized(stateLock) {
            val changed = state != next || detail != nextDetail
            state = next
            detail = nextDetail.take(MAX_DETAIL_LENGTH)
            changed
        }
        if (changed) diagnose("evcc-state", "state=$next detail=${nextDetail.take(MAX_DETAIL_LENGTH)}")
    }

    private fun acceptTelemetry(
        binding: EvccResolvedProperty,
        record: EvccPropertyRecord,
        origin: EvccObservationOrigin,
        eventDetail: String = "",
    ) {
        val cacheChanged = telemetry.accept(binding, record, origin)
        val available = record.status in SUCCESS_STATUSES &&
            record.value?.initialized == true && record.value?.status == EvccApiContract.Status.AVAILABLE
        val property = telemetry.propertyDiagnostic(binding.signal)
        val fingerprint = property.substringBeforeLast(" ageMs=", property)
        val now = SystemClock.elapsedRealtime()
        val shouldTrace = synchronized(stateLock) {
            val previous = propertyTraceStates[binding.signal]
            val minimumInterval = if (binding.signal == EvccLogicalSignal.VEHICLE_SPEED) {
                MOTION_TRACE_INTERVAL_MILLIS
            } else {
                PROPERTY_HEARTBEAT_MILLIS
            }
            val changedValue = previous?.fingerprint != fingerprint
            val trace = previous == null || !available ||
                (binding.signal != EvccLogicalSignal.VEHICLE_SPEED && changedValue) ||
                now - (previous?.recordedAtMillis ?: 0L) >= minimumInterval
            if (trace) propertyTraceStates[binding.signal] = PropertyTraceState(now, fingerprint)
            trace
        }
        if (shouldTrace) {
            val event = eventDetail.takeIf(String::isNotBlank)?.let { "$it " }.orEmpty()
            diagnose(
                "evcc-property",
                "origin=$origin available=$available cacheChanged=$cacheChanged " +
                    "recordStatus=${record.status} valueStatus=${record.value?.status ?: "missing"} " +
                    event + property,
            )
        }
    }

    private fun diagnose(stage: String, detail: String) {
        Log.i(TAG, VehicleDataDiagnostics.record(stage, detail))
    }

    private fun EvccPropertyDescriptor.readable(): Boolean {
        val normalized = access.uppercase(Locale.ROOT)
        return normalized == "R" || normalized == "RW" || "READ" in normalized
    }

    private fun EvccPropertyDescriptor.supports(signal: EvccLogicalSignal): Boolean {
        val normalized = dataType.uppercase(Locale.ROOT)
        return normalized in when (signal) {
            EvccLogicalSignal.BATTERY_SOC,
            EvccLogicalSignal.RANGE_TOTAL_KM,
            EvccLogicalSignal.RANGE_GASOLINE_KM,
            EvccLogicalSignal.RANGE_EV_KM,
            EvccLogicalSignal.VEHICLE_SPEED,
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT,
            EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT,
            EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT,
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT,
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT,
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT,
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT,
            -> NUMBER_TYPES

            EvccLogicalSignal.CHARGING,
            EvccLogicalSignal.GEAR,
            EvccLogicalSignal.CLUSTER_MAP_VISIBLE,
            -> STATE_TYPES
        }
    }

    private companion object {
        const val TAG = "GeePlay-EVCC"
        const val REQUIRED_API_MAJOR = 1
        const val REQUIRED_API_MINOR = 2
        const val MAX_DETAIL_LENGTH = 160
        const val MOTION_TRACE_INTERVAL_MILLIS = 1_000L
        const val PROPERTY_HEARTBEAT_MILLIS = 30_000L
        val PREWARM_OWNER = Any()
        val TYRE_OWNER = Any()
        val SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
        val REQUIRED_PERMISSIONS = setOf(
            EvccApiContract.Permission.PROPERTY_DIRECTORY,
            EvccApiContract.Permission.PROPERTY_READ,
            EvccApiContract.Permission.PROPERTY_LISTEN,
        )
        val BATTERY_SIGNALS = setOf(
            EvccLogicalSignal.BATTERY_SOC,
            EvccLogicalSignal.RANGE_TOTAL_KM,
            EvccLogicalSignal.RANGE_GASOLINE_KM,
            EvccLogicalSignal.RANGE_EV_KM,
            EvccLogicalSignal.CHARGING,
        )
        val MOTION_SIGNALS = setOf(EvccLogicalSignal.VEHICLE_SPEED, EvccLogicalSignal.GEAR)
        val TYRE_SIGNALS = setOf(
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT,
            EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT,
            EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT,
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT,
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT,
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT,
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT,
        )
        val DYNAMIC_SIGNALS = TYRE_SIGNALS + EvccLogicalSignal.CLUSTER_MAP_VISIBLE
        val DIRECTORY_QUERIES = listOf("TIRE", "TYRE", "TPMS", "NAVI_MODE", "MAP_VISIBLE")
        const val MAX_DIRECTORY_PAGES = 10
        const val CLUSTER_VISIBILITY_MAX_AGE_MILLIS = 5_000L
        val NUMBER_TYPES = setOf("FLOAT", "DOUBLE", "INT", "INT32", "INT64", "LONG", "NUMBER")
        val STATE_TYPES = NUMBER_TYPES + setOf("BOOL", "BOOLEAN", "STRING")
        val TERMINAL_STATES = setOf(
            EvccVehicleDataState.AUTHORIZATION_REQUIRED,
            EvccVehicleDataState.INCOMPATIBLE,
        )
    }

    private data class PropertyTraceState(
        val recordedAtMillis: Long,
        val fingerprint: String,
    )
}
