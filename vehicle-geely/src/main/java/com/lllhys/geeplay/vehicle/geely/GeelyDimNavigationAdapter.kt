package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

/** Clean-room reflective adapter for the ECARX navigation interfaces supplied by the head unit. */
class GeelyDimNavigationAdapter(
    context: Context,
    private val config: GeelyNavigationConfig,
    private val nightMode: () -> Boolean = { true },
    private val onStatus: (GeelyNavigationDriverStatus) -> Unit = {},
    private val loadClass: (String) -> Class<*> = {
        Class.forName(it, false, context.applicationContext.classLoader)
    },
    private val laneRenderer: GeelyLaneBitmapRenderer = GeelyLaneBitmapRenderer(),
    private val independentLaneRefresh: Boolean = false,
    private val laneIconId: (AppleLane) -> Int = GeelyLaneIconMapper::iconId,
    private val navigationDataSource: Int? = null,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val navigationStatusRefreshMillis: Long = DEFAULT_NAVIGATION_STATUS_REFRESH_MILLIS,
) : GeelyNavigationDriver {
    private enum class ParameterKind { NUMBER, STRING, BOOLEAN }

    private data class DimMethods(
        val getNavigation: Method,
        val switchPresentation: Method,
        val registerWatcher: Method?,
        val unregisterWatcher: Method?,
        val connect: Method?,
        val disconnect: Method?,
    )

    private data class NavigationMethods(
        val updateInformation: Method,
        val updateStatus: Method,
        val started: Method,
        val stopped: Method,
        val destination: Method,
        val nextDistance: Method,
        val arrow: Method,
        val eta: Method,
        val road: Method,
        val updateLanes: Method?,
        val notifyDataSource: Method?,
        val registerCallback: Method?,
        val unregisterCallback: Method?,
    )

    private data class Information(
        val status: Int = 0,
        val turnId: Int = 0,
        val nextDistance: Long = 0,
        val destination: Long = 0,
        val eta: Long = 0,
        val road: String = "",
        val lanes: Any? = null,
    )

    private val appContext = context.applicationContext
    private val callbackGeneration = AtomicLong()
    private val callbackLock = Any()
    private var executionGuard: () -> Boolean = { true }
    private var dim: Any? = null
    private var navigation: Any? = null
    private var dimMethods: DimMethods? = null
    private var navigationMethods: NavigationMethods? = null
    private var informationProxy: Any? = null
    private var watcher: Any? = null
    private var navigationCallback: Any? = null
    private var watcherRegistered = false
    private var callbackRegistered = false
    private var connectAttempted = false
    private var switchAttempted = false
    private var navigationTouched = false
    private var navigationActive = false
    private var independentLanesInitialized = false
    private var independentLanesVisible = false
    private var lastNavigationStatusRefreshMillis: Long? = null
    private var initialized = false
    @Volatile private var connectedSignal = false
    @Volatile private var disconnectedSignal = false
    @Volatile override var failed = false
        private set
    @Volatile override var cleanupPending = false
        private set
    @Volatile private var closed = false
    @Volatile private var information = Information()

    override fun setExecutionGuard(guard: () -> Boolean) {
        executionGuard = guard
    }

    @Synchronized
    override fun start(): Boolean {
        if (closed || failed || !mayExecute()) return false
        if (initialized) return consumeConnection(tryImmediately = true)
        status(GeelyNavigationDriverState.INITIALIZING, "loading ECARX navigation interfaces")
        return try {
            val generation = callbackGeneration.incrementAndGet()
            val informationType = loadClass(NAVIGATION_INFORMATION)
            val laneType = loadClass(LANE_INFORMATION)
            validateInformationInterface(informationType, laneType)
            informationProxy = proxy(informationType) { proxy, method, arguments ->
                objectMethod(proxy, method, arguments) ?: informationValue(method, information)
            }

            val factoryType = loadClass(DIM_INTERACTION)
            val factory = factoryType.getMethod("create", Context::class.java)
            require(Modifier.isStatic(factory.modifiers) && !factory.returnType.isPrimitive) {
                "Unsupported navigation factory"
            }
            checkConnection()
            val created = invoke(factory, null, appContext)
                ?: error("Navigation factory returned no interface")
            dim = created
            var methods = DimMethods(
                getNavigation = required(created, "getNaviInteraction"),
                switchPresentation = required(
                    created,
                    "setPresentationToDimSwitch",
                    ParameterKind.NUMBER,
                    ParameterKind.STRING,
                    ParameterKind.STRING,
                    ParameterKind.BOOLEAN,
                ),
                registerWatcher = null,
                unregisterWatcher = null,
                connect = null,
                disconnect = null,
            )
            dimMethods = methods
            val watcherType = watchedConnectionType(created)
            if (watcherType != null) {
                validateVoidCallbacks(watcherType, "onConnected", "onDisConnected")
                methods = methods.copy(
                    registerWatcher = requiredInterface(created, "registerConnectWatcher", watcherType),
                    unregisterWatcher = requiredInterface(created, "unregisterConnectWatcher", watcherType),
                    connect = required(created, "connect"),
                    disconnect = required(created, "disconnect"),
                )
                dimMethods = methods
                watcher = proxy(watcherType) { proxy, method, arguments ->
                    objectMethod(proxy, method, arguments) ?: watcherCallback(generation, method)
                }
                checkConnection()
                watcherRegistered = true
                checkedInvoke(methods.registerWatcher!!, created, watcher!!)
                checkConnection()
                connectAttempted = true
                checkedInvoke(methods.connect!!, created)
            } else {
                connectedSignal = true
            }
            initialized = true
            consumeConnection(tryImmediately = true)
        } catch (error: Throwable) {
            fail("initialization", error)
            false
        }
    }

    @Synchronized
    override fun publish(snapshot: AppleNavigationSnapshot): Boolean {
        if (closed || failed || !initialized || !mayExecute() || !consumeConnection(false)) return false
        val target = navigation ?: return false
        val methods = navigationMethods ?: return false
        return try {
            checkConnection()
            val diagnosticLanes = if (config.laneGuidanceEnabled) {
                snapshot.laneGuidance?.lanes.orEmpty()
            } else {
                emptyList()
            }
            GeelyIntegrationDiagnostics.trace(
                "OEM_PLAN",
                "revision=${snapshot.revision} routeState=${snapshot.routeState.name} " +
                    "maneuverIndex=${snapshot.maneuver.index} maneuverType=${snapshot.maneuver.type} " +
                    "turnId=${GeelyManeuverCodes.turnId(snapshot.maneuver.type, config.backend, config.originalOneOsIcons)} " +
                    "laneEvent=${snapshot.laneGuidance?.eventIndex ?: "none"} laneCount=${diagnosticLanes.size} " +
                    diagnosticLanes.joinToString(prefix = "mappedLanes=[", postfix = "]", separator = ";") { lane ->
                        "pos=${lane.position},status=${lane.status.name},icon=${laneIconId(lane)}"
                    },
            )
            information = Information(
                status = 1,
                turnId = GeelyManeuverCodes.turnId(
                    snapshot.maneuver.type,
                    config.backend,
                    config.originalOneOsIcons,
                ),
                nextDistance = snapshot.nextDistanceMeters.coerceAtLeast(0).toLong(),
                destination = snapshot.remainingMeters.nonNegativeInt(),
                eta = snapshot.remainingSeconds.nonNegativeInt(),
                road = snapshot.maneuver.afterRoad.ifEmpty { snapshot.currentRoad }.safeRoad(),
                lanes = buildLaneArray(snapshot),
            )
            if (!navigationActive) {
                switchAttempted = true
                guardedInvoke(dimMethods!!.switchPresentation, dim!!, 1, "", "", true)
                navigationTouched = true
                navigationDataSource?.let { source ->
                    val method = methods.notifyDataSource
                        ?: error("ECARX navigation data-source API unavailable")
                    guardedInvoke(method, target, source)
                }
                publishIndependentLanes(target, methods, information.lanes)
                guardedInvoke(methods.updateInformation, target, informationProxy!!)
                guardedInvoke(methods.updateStatus, target, 1)
                lastNavigationStatusRefreshMillis = elapsedRealtimeMillis()
                guardedInvoke(methods.started, target)
                navigationActive = true
            } else {
                refreshNavigationStatusIfDue(target, methods)
                publishIndependentLanes(target, methods, information.lanes)
                guardedInvoke(methods.updateInformation, target, informationProxy!!)
            }
            listOf(
                methods.destination to information.destination,
                methods.nextDistance to information.nextDistance,
                methods.arrow to information.turnId.toLong(),
                methods.eta to information.eta,
            ).forEach { (method, value) ->
                guardedInvoke(method, target, value)
            }
            guardedInvoke(methods.road, target, information.road)
            status(GeelyNavigationDriverState.ACTIVE, "navigation published")
            true
        } catch (error: Throwable) {
            fail("publish", error)
            false
        }
    }

    @Synchronized
    override fun clear(): Boolean {
        GeelyIntegrationDiagnostics.trace(
            "OEM_CLEAR",
            "navigationActive=$navigationActive lanesVisible=$independentLanesVisible " +
                "switchAttempted=$switchAttempted navigationTouched=$navigationTouched",
        )
        information = information.copy(status = 0, turnId = 0, nextDistance = 0, destination = 0, eta = 0, road = "", lanes = emptyLaneArray())
        if (!switchAttempted && !navigationTouched && !navigationActive) return true
        status(GeelyNavigationDriverState.CLEARING, "clearing OEM navigation")
        val errors = stopOwnedNavigation()
        cleanupPending = errors.isNotEmpty()
        if (cleanupPending) {
            failed = true
            callbackGeneration.incrementAndGet()
            status(GeelyNavigationDriverState.CLEANUP_PENDING, "OEM navigation cleanup was not confirmed", errors.first())
            return false
        }
        status(if (closed) GeelyNavigationDriverState.CLOSED else GeelyNavigationDriverState.READY, "navigation cleared")
        return true
    }

    /** Debug/validation seam: overlays only lane data without taking or stopping navigation ownership. */
    @Synchronized
    fun publishLanesOnly(lanes: List<AppleLane>): Boolean {
        if (closed || failed || !initialized || !mayExecute() || !consumeConnection(false)) return false
        val target = navigation ?: return false
        val methods = navigationMethods ?: return false
        val update = methods.updateLanes ?: return false
        return try {
            checkConnection()
            navigationDataSource?.let { source ->
                val method = methods.notifyDataSource
                    ?: error("ECARX navigation data-source API unavailable")
                guardedInvoke(method, target, source)
            }
            val laneArray = buildLaneArray(lanes)
            guardedInvoke(update, target, laneArray)
            independentLanesInitialized = true
            independentLanesVisible = lanes.isNotEmpty()
            // G636's DIM service only mutates its pending protobuf in updateLaneInfo(); a following
            // navigation status event flushes that snapshot to the instrument cluster.
            guardedInvoke(methods.updateStatus, target, 1)
            lastNavigationStatusRefreshMillis = elapsedRealtimeMillis()
            true
        } catch (error: Throwable) {
            fail("lane-only publish", error)
            false
        }
    }

    /** Clears only the lane overlay while leaving an OEM-owned navigation session running. */
    @Synchronized
    fun clearLanesOnly(): Boolean {
        if (closed || failed || !initialized || !mayExecute() || !consumeConnection(false)) return false
        val target = navigation ?: return false
        val methods = navigationMethods ?: return false
        val update = methods.updateLanes ?: return false
        return runCatching {
            guardedInvoke(update, target, emptyLaneArray() ?: return false)
            independentLanesInitialized = true
            independentLanesVisible = false
            guardedInvoke(methods.updateStatus, target, 1)
            lastNavigationStatusRefreshMillis = elapsedRealtimeMillis()
            true
        }.getOrElse {
            status(GeelyNavigationDriverState.FAILED, "ECARX lane-only cleanup failed", unwrap(it))
            false
        }
    }

    @Synchronized
    override fun recoverCleanup(): Boolean {
        if (!start()) return false
        switchAttempted = true
        navigationTouched = true
        return clear()
    }

    @Synchronized
    override fun close() {
        if (closed && !cleanupPending) return
        closed = true
        callbackGeneration.incrementAndGet()
        connectedSignal = false
        val errors = ArrayList<Throwable>()
        errors += stopOwnedNavigation()
        errors += releaseConnection()
        cleanupPending = errors.isNotEmpty()
        laneRenderer.clear()
        if (cleanupPending) {
            status(GeelyNavigationDriverState.CLEANUP_PENDING, "OEM navigation close was not confirmed", errors.first())
        } else {
            informationProxy = null
            navigationCallback = null
            watcher = null
            navigation = null
            dim = null
            status(GeelyNavigationDriverState.CLOSED, "navigation driver closed")
        }
    }

    private fun consumeConnection(tryImmediately: Boolean): Boolean {
        if (closed || failed || disconnectedSignal || !mayExecute()) return false
        if (navigation != null) return true
        if (!connectedSignal && !tryImmediately) {
            status(GeelyNavigationDriverState.WAITING_FOR_SERVICE, "waiting for ECARX navigation service")
            return false
        }
        return try {
            checkConnection()
            val methods = dimMethods ?: error("DIM methods unavailable")
            val target = invoke(methods.getNavigation, dim!!)
            if (target == null) {
                if (config.connectionMode == GeelyConnectionMode.DIRECT || connectedSignal) {
                    error("Connected SDK returned no navigation interface")
                }
                status(GeelyNavigationDriverState.WAITING_FOR_SERVICE, "waiting for ECARX navigation service")
                false
            } else {
                val informationType = informationProxy!!.javaClass.interfaces.single()
                val callbackType = loadClass(NAVIGATION_CALLBACK).also {
                    require(it.isInterface) { "Navigation callback is not an interface" }
                }
                val laneType = loadClass(LANE_INFORMATION)
                val resolved = NavigationMethods(
                    updateInformation = requiredInterface(target, "updateNavigationInfo", informationType),
                    updateStatus = required(target, "notifyNavigationStatus", ParameterKind.NUMBER),
                    started = required(target, "notifyTurnByTurnStarted"),
                    stopped = required(target, "notifyTurnByTurnStopped"),
                    destination = required(target, "updateDistanceToDestination", ParameterKind.NUMBER),
                    nextDistance = required(target, "updateDistanceToNextGuidancePoint", ParameterKind.NUMBER),
                    arrow = required(target, "updateTurnByTurnArrow", ParameterKind.NUMBER),
                    eta = required(target, "updateETA", ParameterKind.NUMBER),
                    road = required(target, "updateNextGuidancePointName", ParameterKind.STRING),
                    updateLanes = laneType?.let { optionalInterface(target, "updateLaneInfo", ReflectArray.newInstance(it, 0).javaClass) },
                    notifyDataSource = optional(target, "notifyDataSource", ParameterKind.NUMBER),
                    registerCallback = requiredInterface(target, "registerNavigationInteractionCallback", callbackType),
                    unregisterCallback = requiredInterface(target, "unregisterNavigationInteractionCallback", callbackType),
                )
                navigation = target
                navigationMethods = resolved
                navigationCallback = proxy(callbackType) { proxy, method, arguments ->
                    objectMethod(proxy, method, arguments) ?: defaultValue(method.returnType)
                }
                callbackRegistered = true
                checkedInvoke(resolved.registerCallback!!, target, navigationCallback!!)
                status(GeelyNavigationDriverState.READY, "ECARX navigation service connected")
                true
            }
        } catch (error: Throwable) {
            fail("connection", error)
            false
        }
    }

    private fun buildLaneArray(snapshot: AppleNavigationSnapshot): Any? {
        val lanes = if (config.laneGuidanceEnabled) snapshot.laneGuidance?.lanes.orEmpty() else emptyList()
        return buildLaneArray(lanes)
    }

    private fun buildLaneArray(lanes: List<AppleLane>): Any {
        val laneType = loadClass(LANE_INFORMATION)
        return ReflectArray.newInstance(laneType, lanes.size).also { array ->
            lanes.forEachIndexed { index, lane ->
                ReflectArray.set(array, index, laneProxy(laneType, lane))
            }
        }
    }

    private fun emptyLaneArray(): Any? = runCatching {
        ReflectArray.newInstance(loadClass(LANE_INFORMATION), 0)
    }.getOrNull()

    private fun publishIndependentLanes(target: Any, methods: NavigationMethods, lanes: Any?) {
        if (!independentLaneRefresh || methods.updateLanes == null || lanes == null) return
        val visible = ReflectArray.getLength(lanes) > 0
        if (!visible && independentLanesInitialized && !independentLanesVisible) return
        // G636 flushes updateNavigationInfo when its ETA field is handled. Populate the shared DIM
        // protobuf first. The first publication always writes an array so a new process clears any
        // lane state left in the system service by a previous owner; later empty arrays are deduped.
        guardedInvoke(methods.updateLanes, target, lanes)
        independentLanesInitialized = true
        independentLanesVisible = visible
        GeelyIntegrationDiagnostics.trace(
            "OEM_LANE_WRITE",
            "visible=$visible count=${ReflectArray.getLength(lanes)}",
        )
    }

    private fun refreshNavigationStatusIfDue(target: Any, methods: NavigationMethods) {
        val now = elapsedRealtimeMillis()
        val previous = lastNavigationStatusRefreshMillis
        if (previous != null && now - previous < navigationStatusRefreshMillis) return
        guardedInvoke(methods.updateStatus, target, 1)
        lastNavigationStatusRefreshMillis = now
        GeelyIntegrationDiagnostics.trace(
            "OEM_STATUS_REFRESH",
            "status=1 intervalMs=${if (previous == null) "initial" else now - previous}",
        )
    }

    private fun laneProxy(type: Class<*>, lane: AppleLane): Any {
        val bitmap by lazy { laneRenderer.render(lane, nightMode()) }
        return proxy(type) { proxy, method, arguments ->
            objectMethod(proxy, method, arguments) ?: when (method.name) {
                "getLaneBackIcon" -> bitmap
                "getLaneBackIconId" -> number(method.returnType, laneIconId(lane).toLong())
                "getLaneNumber" -> number(method.returnType, (lane.position + config.laneNumberBase).toLong())
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun informationValue(method: Method, value: Information): Any? = when (method.name) {
        "getNavigationStatus" -> number(method.returnType, value.status.toLong())
        "getNavigationTurnId" -> number(method.returnType, value.turnId.toLong())
        "getDistanceToNextGuidancePoint" -> number(method.returnType, value.nextDistance)
        "getDistanceToDestination" -> number(method.returnType, value.destination)
        "getETA" -> number(method.returnType, value.eta)
        "getNextGuidancePointName" -> value.road
        "getDayNightMode", "getDrivingDirection" -> number(method.returnType, 1)
        "getMuteState" -> number(method.returnType, 0)
        "getExtensionInfo" -> Bundle()
        "getLaneInfo" -> value.lanes ?: emptyLaneArray()
        "getNavigationTurnSVG" -> null
        else -> defaultValue(method.returnType)
    }

    private fun watcherCallback(generation: Long, method: Method): Any? {
        synchronized(callbackLock) {
            if (!closed && !failed && callbackGeneration.get() == generation) {
                when (method.name) {
                    "onConnected" -> connectedSignal = true
                    "onDisConnected" -> disconnectedSignal = true
                }
            }
        }
        return defaultValue(method.returnType)
    }

    private fun stopOwnedNavigation(): List<Throwable> {
        if (!switchAttempted && !navigationTouched && !navigationActive) return emptyList()
        val errors = ArrayList<Throwable>()
        val target = navigation
        val methods = navigationMethods
        if (target != null && methods != null) {
            methods.updateLanes?.takeIf { independentLaneRefresh && independentLanesVisible }?.let { update ->
                emptyLaneArray()?.let { lanes -> attempt(errors) { checkedInvoke(update, target, lanes) } }
            }
            informationProxy?.let { proxy -> attempt(errors) { checkedInvoke(methods.updateInformation, target, proxy) } }
            attempt(errors) { checkedInvoke(methods.updateStatus, target, 0) }
            attempt(errors) { checkedInvoke(methods.stopped, target) }
        }
        if (switchAttempted && dim != null && dimMethods != null) {
            attempt(errors) { checkedInvoke(dimMethods!!.switchPresentation, dim!!, 1, "", "", false) }
        }
        navigationActive = false
        independentLanesInitialized = false
        independentLanesVisible = false
        lastNavigationStatusRefreshMillis = null
        if (errors.isEmpty()) {
            switchAttempted = false
            navigationTouched = false
        }
        return errors
    }

    private fun releaseConnection(): List<Throwable> {
        val errors = ArrayList<Throwable>()
        val target = navigation
        val methods = navigationMethods
        val callback = navigationCallback
        if (target != null && methods != null && callbackRegistered && callback != null) {
            methods.unregisterCallback?.let { method ->
                attempt(errors) { checkedInvoke(method, target, callback) }
            }
            if (errors.isEmpty()) callbackRegistered = false
        }
        val dimTarget = dim
        val dimApi = dimMethods
        val connectWatcher = watcher
        if (dimTarget != null && dimApi != null && watcherRegistered && connectWatcher != null) {
            dimApi.unregisterWatcher?.let { method ->
                attempt(errors) { checkedInvoke(method, dimTarget, connectWatcher) }
            }
            if (errors.isEmpty()) watcherRegistered = false
        }
        if (dimTarget != null && dimApi != null && connectAttempted) {
            dimApi.disconnect?.let { method ->
                attempt(errors) { checkedInvoke(method, dimTarget) }
            }
            if (errors.isEmpty()) connectAttempted = false
        }
        return errors
    }

    private fun fail(stage: String, error: Throwable) {
        failed = true
        callbackGeneration.incrementAndGet()
        val cleanupErrors = stopOwnedNavigation() + releaseConnection()
        cleanupPending = cleanupErrors.isNotEmpty()
        status(
            if (cleanupPending) GeelyNavigationDriverState.CLEANUP_PENDING else GeelyNavigationDriverState.FAILED,
            "ECARX navigation $stage failed",
            unwrap(error),
        )
    }

    private fun checkConnection() {
        check(!closed && !failed && !disconnectedSignal && mayExecute()) {
            "Navigation connection is no longer usable"
        }
    }

    private fun mayExecute(): Boolean = runCatching(executionGuard).getOrDefault(false)

    private fun status(state: GeelyNavigationDriverState, stage: String, error: Throwable? = null) {
        runCatching { onStatus(GeelyNavigationDriverStatus(state, stage, error?.javaClass?.simpleName)) }
    }

    private fun validateInformationInterface(type: Class<*>, laneType: Class<*>?) {
        require(type.isInterface) { "Navigation information must be an interface" }
        listOf(
            "getDayNightMode",
            "getDistanceToDestination",
            "getDistanceToNextGuidancePoint",
            "getDrivingDirection",
            "getETA",
            "getNavigationStatus",
            "getNavigationTurnId",
            "getMuteState",
        ).forEach { name -> require(isNumber(type.getMethod(name).returnType)) { "Unsupported $name return type" } }
        require(type.getMethod("getNextGuidancePointName").returnType == String::class.java)
        require(type.getMethod("getExtensionInfo").returnType == Bundle::class.java)
        val laneGetter = type.methods.singleOrNull { it.name == "getLaneInfo" && it.parameterTypes.isEmpty() }
        require(laneType != null && laneType.isInterface) { "ECARX lane interface unavailable" }
        require(laneGetter != null && laneGetter.returnType.isArray && laneGetter.returnType.componentType == laneType) {
            "Unsupported lane information getter"
        }
        require(laneType.getMethod("getLaneBackIcon").returnType == Bitmap::class.java)
        require(isNumber(laneType.getMethod("getLaneBackIconId").returnType))
        require(isNumber(laneType.getMethod("getLaneNumber").returnType))
    }

    /** AUTO uses the complete watched contract when present and otherwise takes the direct path. */
    private fun watchedConnectionType(target: Any): Class<*>? {
        if (config.connectionMode == GeelyConnectionMode.DIRECT) return null
        val watcherType = runCatching { loadClass(CONNECT_WATCHER) }.getOrNull()
        if (config.backend == GeelyNavigationBackend.ECARX_FLYME) {
            return watcherType ?: throw ClassNotFoundException(CONNECT_WATCHER)
        }
        if (watcherType == null || !watcherType.isInterface) return null
        val complete = optionalInterface(target, "registerConnectWatcher", watcherType) != null &&
            optionalInterface(target, "unregisterConnectWatcher", watcherType) != null &&
            optional(target, "connect") != null &&
            optional(target, "disconnect") != null
        return watcherType.takeIf { complete }
    }

    private fun validateVoidCallbacks(type: Class<*>, vararg names: String) {
        require(type.isInterface) { "Connection watcher is not an interface" }
        names.forEach { name -> require(type.getMethod(name).returnType == Void.TYPE) { "Unsupported $name callback" } }
    }

    private fun required(target: Any, name: String, vararg kinds: ParameterKind): Method =
        find(target, name, kinds.toList()) ?: throw NoSuchMethodException("Unsupported or ambiguous $name")

    private fun optional(target: Any, name: String, vararg kinds: ParameterKind): Method? =
        find(target, name, kinds.toList())

    private fun requiredInterface(target: Any, name: String, type: Class<*>): Method =
        findInterface(target, name, type) ?: throw NoSuchMethodException("Unsupported or ambiguous $name")

    private fun optionalInterface(target: Any, name: String, type: Class<*>): Method? =
        findInterface(target, name, type)

    private fun find(target: Any, name: String, kinds: List<ParameterKind>): Method? {
        val matches = target.javaClass.methods.filter { method ->
            method.name == name && !method.isBridge && !method.isSynthetic &&
                method.parameterTypes.size == kinds.size &&
                method.parameterTypes.indices.all { index -> accepts(method.parameterTypes[index], kinds[index]) } &&
                validOperationReturn(method.returnType, name == "getNaviInteraction")
        }
        return matches.singleOrNull()
    }

    private fun findInterface(target: Any, name: String, type: Class<*>): Method? {
        val matches = target.javaClass.methods.filter { method ->
            method.name == name && !method.isBridge && !method.isSynthetic &&
                method.parameterTypes.contentEquals(arrayOf(type)) && validOperationReturn(method.returnType, false)
        }
        return matches.singleOrNull()
    }

    private fun validOperationReturn(type: Class<*>, objectResult: Boolean): Boolean =
        if (objectResult) !type.isPrimitive else type == Void.TYPE || type == Boolean::class.java || isNumber(type)

    private fun accepts(type: Class<*>, kind: ParameterKind): Boolean = when (kind) {
        ParameterKind.NUMBER -> isNumber(type)
        ParameterKind.STRING -> type == String::class.java
        ParameterKind.BOOLEAN -> type == Boolean::class.java || type == Boolean::class.javaPrimitiveType
    }

    private fun isNumber(type: Class<*>): Boolean =
        type == Byte::class.java || type == Byte::class.javaPrimitiveType ||
            type == Short::class.java || type == Short::class.javaPrimitiveType ||
            type == Int::class.java || type == Int::class.javaPrimitiveType ||
            type == Long::class.java || type == Long::class.javaPrimitiveType

    private fun checkedInvoke(method: Method, target: Any?, vararg arguments: Any?) {
        val result = try {
            invoke(method, target, *arguments)
        } catch (error: Throwable) {
            GeelyIntegrationDiagnostics.oemCall(method.name, "exception:${unwrap(error).javaClass.simpleName}")
            throw error
        }
        if (result == false || result is Number && result.toLong() < 0) {
            GeelyIntegrationDiagnostics.oemCall(method.name, "rejected")
            error("OEM navigation call rejected")
        }
        GeelyIntegrationDiagnostics.oemCall(method.name, "accepted")
    }

    private fun guardedInvoke(method: Method, target: Any?, vararg arguments: Any?) {
        checkConnection()
        checkedInvoke(method, target, *arguments)
    }

    private fun invoke(method: Method, target: Any?, vararg arguments: Any?): Any? {
        val converted = arguments.mapIndexed { index, argument ->
            if (argument is Number) number(method.parameterTypes[index], argument.toLong()) else argument
        }.toTypedArray()
        return method.invoke(target, *converted)
    }

    private fun proxy(type: Class<*>, handler: (Any, Method, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), InvocationHandler(handler))

    private fun objectMethod(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? = when (method.name) {
        "toString" -> "GeePlayGeelyNavigationProxy"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.firstOrNull()
        else -> null
    }

    private fun number(type: Class<*>, value: Long): Any = when (type) {
        Byte::class.java, Byte::class.javaPrimitiveType -> value.toByte()
        Short::class.java, Short::class.javaPrimitiveType -> value.toShort()
        Int::class.java, Int::class.javaPrimitiveType -> value.toInt()
        Long::class.java, Long::class.javaPrimitiveType -> value
        else -> error("Unsupported numeric type ${type.name}")
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
    }

    private fun Long?.nonNegativeInt(): Long = this?.coerceIn(0, Int.MAX_VALUE.toLong()) ?: 0

    private fun String.safeRoad(): String {
        if (length <= MAX_ROAD_UTF16_UNITS) return this
        var end = MAX_ROAD_UTF16_UNITS
        if (end > 0 && Character.isHighSurrogate(this[end - 1])) end -= 1
        return substring(0, end)
    }

    private fun attempt(errors: MutableList<Throwable>, action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let(errors::add)
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.lang.reflect.InvocationTargetException) error.targetException else error

    companion object {
        const val DIM_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
        const val NAVIGATION_INFORMATION =
            "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo"
        const val LANE_INFORMATION =
            "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$ILaneInfo"
        const val NAVIGATION_CALLBACK =
            "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInteractionCallback"
        const val CONNECT_WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher"
        private const val MAX_ROAD_UTF16_UNITS = 512
        private const val DEFAULT_NAVIGATION_STATUS_REFRESH_MILLIS = 5_000L
    }
}
