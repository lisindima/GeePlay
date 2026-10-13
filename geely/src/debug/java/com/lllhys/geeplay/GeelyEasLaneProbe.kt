package com.lllhys.geeplay

import android.content.Context
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only caller for the newer EAS Map-provider channel used by the G636 factory map.
 *
 * The vendor SDK is loaded from an installed system map package at runtime, so GeePlay does not
 * redistribute proprietary SDK binaries. Status 16 establishes this package as the active guiding
 * provider before lane data is sent; 43/44 only mirror the factory map's crossroad-enlargement
 * lifecycle. This class is not wired into production navigation until the complete provider
 * lifecycle is confirmed on a physical head unit.
 */
class GeelyEasLaneProbe(private val context: Context) {
    private data class Session(
        val sdkSourcePackage: String,
        val loader: ClassLoader,
        val mapApi: Any,
        val mapClient: Any,
        val baseClientType: Class<*>,
        val eventListenerType: Class<*>,
        val registration: Any,
        val registeredMapVendor: Int,
        val eventListener: Any,
    )

    fun run(
        lanes: List<Pair<Int, Int>>,
        durationMillis: Long,
        onVisible: () -> Unit = {},
        onBeforeHidden: () -> Unit = {},
    ): String {
        var session: Session? = null
        var guideStarted = false
        var crossroadShown = false
        var published = false
        var cleared = false
        var crossroadHidden = false
        var guideStopped = false
        var failure: Throwable? = null
        try {
            session = openSession()
            check(session.registeredMapVendor != UNKNOWN_MAP_VENDOR) {
                "NaviService registered GeePlay as unknown vendor $UNKNOWN_MAP_VENDOR; " +
                    "a supported provider identity is required before vehicle output"
            }
            publishStatus(session, MAP_STATUS_GUIDE_START)
            guideStarted = true
            publishStatus(session, MAP_STATUS_CROSSROAD_ON)
            crossroadShown = true
            publishLanes(session, lanes, visible = true)
            published = true
            onVisible()
            Thread.sleep(durationMillis)
            onBeforeHidden()
            publishLanes(session, emptyList(), visible = false)
            cleared = true
            publishStatus(session, MAP_STATUS_CROSSROAD_OFF)
            crossroadHidden = true
            publishStatus(session, MAP_STATUS_GUIDE_STOP)
            guideStopped = true
        } catch (error: Throwable) {
            failure = unwrap(error)
            if (guideStarted) session?.let(::clearProviderState)
        } finally {
            session?.let(::closeSession)
        }
        return buildString {
            appendLine("provider=${context.packageName} sdkSource=${session?.sdkSourcePackage ?: "none"}")
            appendLine("apiReady=${session != null} registered=${session != null} listenerReady=${session != null}")
            appendLine("requestedMapVendor=unset registeredMapVendor=${session?.registeredMapVendor ?: -1}")
            appendLine(
                "guideStarted=$guideStarted crossroadShown=$crossroadShown published=$published " +
                    "cleared=$cleared crossroadHidden=$crossroadHidden guideStopped=$guideStopped",
            )
            appendLine("result=${if (failure == null && cleared && guideStopped) "DELIVERED" else "FAILED"}")
            failure?.let { appendLine("error=${it.javaClass.name}:${it.message}") }
        }
    }

    private fun openSession(): Session {
        val (sdkPackage, loader) = findSdkLoader()
        val mapApiType = loader.loadClass(MAP_API)
        val callbackType = loader.loadClass(API_CALLBACK)
        val mapClientType = loader.loadClass(MAP_CLIENT)
        val baseClientType = loader.loadClass(BASE_MAP_CLIENT)
        val eventListenerType = loader.loadClass(MAP_EVENT_LISTENER)
        val mapClientInfoType = loader.loadClass(MAP_CLIENT_INFO)
        val apiReady = AtomicBoolean(false)
        val apiLatch = CountDownLatch(1)
        val listenerRef = AtomicReference<Any?>()
        val listenerLatch = CountDownLatch(1)
        val callback = Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, arguments ->
            objectMethod(proxy, method, arguments) ?: when (method.name) {
                "onAPIReady" -> {
                    apiReady.set(arguments?.firstOrNull() == true)
                    apiLatch.countDown()
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
        val mapClient = Proxy.newProxyInstance(loader, arrayOf(mapClientType)) { proxy, method, arguments ->
            objectMethod(proxy, method, arguments) ?: when (method.name) {
                "setMapEventListener" -> {
                    listenerRef.set(arguments?.firstOrNull())
                    listenerLatch.countDown()
                    null
                }
                "invokeMapAPI" -> arguments?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
        val mapApi = mapApiType.getMethod("get", Context::class.java).invoke(null, context)
            ?: error("EAS MapAPI.get returned null")
        mapApi.javaClass.getMethod("init", Context::class.java, callbackType).invoke(mapApi, context, callback)
        check(apiLatch.await(API_TIMEOUT_SECONDS, TimeUnit.SECONDS) && apiReady.get()) {
            "EAS MapAPI did not become ready"
        }
        val info = mapClientInfoType.getConstructor().newInstance()
        info.javaClass.getMethod("setPackageName", String::class.java).invoke(info, context.packageName)
        val clients = ReflectArray.newInstance(baseClientType, 1).also { ReflectArray.set(it, 0, mapClient) }
        val register = mapApi.javaClass.getMethod("registerMapClient", mapClientInfoType, clients.javaClass)
        val registration = register.invoke(mapApi, info, clients)
            ?: error("EAS registerMapClient returned null")
        val errorCode = registration.javaClass.methods.singleOrNull {
            it.name == "getErrorCode" && it.parameterTypes.isEmpty()
        }?.invoke(registration) as? Number
        check(errorCode == null || errorCode.toInt() == NAVI_SUCCESS) {
            "EAS registerMapClient failed with ${errorCode?.toInt()}"
        }
        val registeredMapVendor = (registration.javaClass.getMethod("getMapVendor").invoke(registration) as Number).toInt()
        check(listenerLatch.await(LISTENER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "EAS map event listener was not attached"
        }
        val eventListener = listenerRef.get() ?: error("EAS map event listener is null")
        return Session(
            sdkPackage,
            loader,
            mapApi,
            mapClient,
            baseClientType,
            eventListenerType,
            registration,
            registeredMapVendor,
            eventListener,
        )
    }

    private fun publishStatus(session: Session, status: Int) {
        session.eventListenerType.getMethod("onMapStatusChanged", Int::class.javaPrimitiveType)
            .invoke(session.eventListener, status)
    }

    private fun publishLanes(session: Session, lanes: List<Pair<Int, Int>>, visible: Boolean) {
        val lanesInfoType = session.loader.loadClass(LANES_INFO)
        val laneItemType = session.loader.loadClass(LANE_ITEM)
        val lanesInfo = lanesInfoType.getConstructor().newInstance()
        lanesInfoType.getMethod("copyBaseInfo", session.loader.loadClass(NAVI_BASE_MODEL))
            .invoke(lanesInfo, session.registration)
        lanesInfoType.getMethod("setPackageName", String::class.java)
            .invoke(lanesInfo, context.packageName)
        val items = ArrayList<Any>(lanes.size)
        lanes.forEach { (index, iconId) ->
            val item = laneItemType.getConstructor().newInstance()
            laneItemType.getMethod("setLaneIndex", Int::class.javaPrimitiveType).invoke(item, index)
            laneItemType.getMethod("setLaneIconId", Int::class.javaPrimitiveType).invoke(item, iconId)
            items += item
        }
        lanesInfoType.getMethod("setLaneItemList", List::class.java).invoke(lanesInfo, items)
        lanesInfoType.getMethod("setLanesEnabled", Boolean::class.javaPrimitiveType).invoke(lanesInfo, visible)
        session.eventListenerType.getMethod("onLanesInfoChanged", lanesInfoType)
            .invoke(session.eventListener, lanesInfo)
    }

    private fun clearProviderState(session: Session) {
        runCatching { publishLanes(session, emptyList(), visible = false) }
        runCatching { publishStatus(session, MAP_STATUS_CROSSROAD_OFF) }
        runCatching { publishStatus(session, MAP_STATUS_GUIDE_STOP) }
    }

    private fun closeSession(session: Session) {
        runCatching {
            val clients = ReflectArray.newInstance(session.baseClientType, 1).also {
                ReflectArray.set(it, 0, session.mapClient)
            }
            session.mapApi.javaClass.getMethod("unregisterMapClient", clients.javaClass)
                .invoke(session.mapApi, clients)
        }
        runCatching { session.mapApi.javaClass.getMethod("release").invoke(session.mapApi) }
    }

    private fun findSdkLoader(): Pair<String, ClassLoader> {
        val candidates = buildList {
            add("self" to context.classLoader)
            SDK_PACKAGES.forEach { packageName ->
                runCatching {
                    val packageContext = context.createPackageContext(
                        packageName,
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                    )
                    add(packageName to packageContext.classLoader)
                }
            }
        }
        return candidates.firstOrNull { (_, loader) -> runCatching { loader.loadClass(MAP_API) }.isSuccess }
            ?: error("EAS Map SDK classes are unavailable")
    }

    private fun objectMethod(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? = when (method.name) {
        "toString" -> "GeePlayEasLaneProbeProxy"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.firstOrNull()
        else -> null
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
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

    private fun unwrap(error: Throwable): Throwable =
        (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error

    companion object {
        private val SDK_PACKAGES = listOf("com.flyme.auto.map", "ecarx.naviservice")
        private const val MAP_API = "com.ecarx.eas.framework.sdk.navi.MapAPI"
        private const val API_CALLBACK = "com.ecarx.eas.sdk.ECarXApiClient\$Callback"
        private const val MAP_CLIENT = "com.ecarx.eas.mapsdk.interfaces.IMapClient"
        private const val BASE_MAP_CLIENT = "com.ecarx.eas.mapsdk.interfaces.IBaseMapClient"
        private const val MAP_EVENT_LISTENER = "com.ecarx.eas.mapsdk.interfaces.IMapEventListener"
        private const val MAP_CLIENT_INFO = "com.ecarx.eas.mapsdk.model.MapClientInfo"
        private const val LANES_INFO = "com.ecarx.eas.mapsdk.model.response.LanesInfo"
        private const val LANE_ITEM = "com.ecarx.eas.mapsdk.model.base.LaneItem"
        private const val NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel"
        private const val NAVI_SUCCESS = 10000
        private const val UNKNOWN_MAP_VENDOR = -100
        private const val MAP_STATUS_GUIDE_START = 16
        private const val MAP_STATUS_GUIDE_STOP = 17
        private const val MAP_STATUS_CROSSROAD_ON = 43
        private const val MAP_STATUS_CROSSROAD_OFF = 44
        private const val API_TIMEOUT_SECONDS = 5L
        private const val LISTENER_TIMEOUT_SECONDS = 5L
    }
}
