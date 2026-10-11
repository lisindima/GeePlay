package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccApiInfo
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSessionInfo
import com.kooo.evcc.evccapi.EvccSourceState
import com.kooo.evcc.evccapi.client.EvccApiClient
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataAdapter
import com.shilapi.xcertplay.vehicle.CarPlayVehicleDataSession
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
) : CarPlayVehicleDataAdapter {
    constructor(context: Context) : this(context, ::OfficialEvccClientPort)

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
            }
            telemetry.clear()
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
                    telemetry.accept(binding, record)
                    refreshReadyState()
                }
            }
        }

        override fun onSourceStateChanged(state: EvccSourceState, sequence: Long) {
            if (state.source !in PROFILE_SOURCES) return
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
        client = clientFactory(appContext, listener)
    }

    override fun prepare() {
        if (closed.get()) return
        demandRegistry.replace(PREWARM_OWNER, preferenceSignals())
        reconcileDemand()
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
            EvccVehicleSpeedSource(telemetry, requestRefresh = ::requestMotionRefresh)
        } else {
            null
        }
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
            "state=$state service=$serviceVersion " +
                "api=$protocolVersion contract=${EvccApiContract.CONTRACT_HASH} " +
                "permissions=${grantedPermissions.sorted()} demand=${demand.map { it.name }.sorted()} " +
                "sources=$sources resolved=${resolved.map { "${it.signal.name}:${it.selector.id}" }.sorted()} " +
                "sessions=${activeSessionOwners.size} detail=$detail"
        }
        return "EVCC vehicle data: installed=${evccInstalled()} $snapshot ready=$ready"
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
            val latestSourceStates = client.querySourceStates()
                .filter { it.source in PROFILE_SOURCES }
                .associateBy(EvccSourceState::source)
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
            if (!current(expectedGeneration)) return
            val subscription = client.replaceSubscriptions(next.map(EvccResolvedProperty::selector))
            if (!subscription.isSuccess) {
                updateState(EvccVehicleDataState.DEGRADED, "subscription:${subscription.status}")
                return
            }
            if (!current(expectedGeneration)) return
            synchronized(stateLock) { resolved = next }
            telemetry.retainSignals(next.mapTo(linkedSetOf(), EvccResolvedProperty::signal))
            for (binding in next) {
                if (!current(expectedGeneration)) return
                val record = client.readProperty(binding.selector, forceRefresh = false)
                if (!current(expectedGeneration)) return
                telemetry.accept(binding, record)
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

    private fun requestMotionRefresh() = requestRefresh(MOTION_SIGNALS)

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
                    val record = client.readProperty(binding.selector, forceRefresh = false)
                    if (!current(expectedGeneration)) break
                    telemetry.accept(binding, record)
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
        val motionDemanded = demand.any { it in MOTION_SIGNALS }
        val batteryReady = telemetry.vehicleStatus(EvccVehicleDataSettings.lowChargePercent(appContext)) != null
        val motionReady = telemetry.motion(maxAgeMillis = Long.MAX_VALUE) != null
        val complete = (!batteryDemanded || batteryReady) && (!motionDemanded || motionReady)
        updateState(
            if (complete && demand.isNotEmpty()) EvccVehicleDataState.READY else EvccVehicleDataState.DEGRADED,
            "battery=$batteryReady motion=$motionReady",
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
        }
    }

    private fun demandedSignals(expectedGeneration: Long): Set<EvccLogicalSignal>? =
        synchronized(stateLock) { demandedSignals.takeIf { current(expectedGeneration) } }

    private fun preferenceSignals(): Set<EvccLogicalSignal> = buildSet {
        if (EvccVehicleDataSettings.vehicleStatusEnabled(appContext)) addAll(BATTERY_SIGNALS)
        if (EvccVehicleDataSettings.vehicleSpeedEnabled(appContext)) addAll(MOTION_SIGNALS)
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
        if (changed) Log.i(TAG, "state=$next detail=${nextDetail.take(MAX_DETAIL_LENGTH)}")
    }

    private fun EvccPropertyDescriptor.readable(): Boolean {
        val normalized = access.uppercase(Locale.ROOT)
        return normalized == "R" || normalized == "RW" || "READ" in normalized
    }

    private fun EvccPropertyDescriptor.supports(signal: EvccLogicalSignal): Boolean {
        val normalized = dataType.uppercase(Locale.ROOT)
        return normalized in when (signal) {
            EvccLogicalSignal.BATTERY_SOC,
            EvccLogicalSignal.RANGE_EV_KM,
            EvccLogicalSignal.VEHICLE_SPEED,
            -> NUMBER_TYPES

            EvccLogicalSignal.CHARGING,
            EvccLogicalSignal.GEAR,
            -> STATE_TYPES
        }
    }

    private companion object {
        const val TAG = "GeePlay-EVCC"
        const val REQUIRED_API_MAJOR = 1
        const val REQUIRED_API_MINOR = 2
        const val MAX_DETAIL_LENGTH = 160
        val PREWARM_OWNER = Any()
        val SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
        val REQUIRED_PERMISSIONS = setOf(
            EvccApiContract.Permission.PROPERTY_DIRECTORY,
            EvccApiContract.Permission.PROPERTY_READ,
            EvccApiContract.Permission.PROPERTY_LISTEN,
        )
        val PROFILE_SOURCES = EvccGeelyPropertyProfile.bindings
            .flatMap { binding -> binding.candidates.map(EvccPropertyCandidate::source) }
            .toSet()
        val BATTERY_SIGNALS = setOf(
            EvccLogicalSignal.BATTERY_SOC,
            EvccLogicalSignal.RANGE_EV_KM,
            EvccLogicalSignal.CHARGING,
        )
        val MOTION_SIGNALS = setOf(EvccLogicalSignal.VEHICLE_SPEED, EvccLogicalSignal.GEAR)
        val NUMBER_TYPES = setOf("FLOAT", "DOUBLE", "INT", "INT32", "INT64", "LONG", "NUMBER")
        val STATE_TYPES = NUMBER_TYPES + setOf("BOOL", "BOOLEAN", "STRING")
        val TERMINAL_STATES = setOf(
            EvccVehicleDataState.AUTHORIZATION_REQUIRED,
            EvccVehicleDataState.INCOMPATIBLE,
        )
    }
}
