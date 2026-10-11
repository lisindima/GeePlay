package com.shilapi.xcertplay.navigation.runtime

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceChange
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceParser
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceStore
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

enum class RouteGuidanceSourceKind(val preferred: Boolean) {
    WIRED(true),
    WIRELESS_BLUETOOTH_BOOTSTRAP(false),
    WIRELESS_RUNTIME_TUNNEL(true),
}

class RouteGuidanceSource internal constructor(
    val id: Long,
    val kind: RouteGuidanceSourceKind,
) {
    companion object {
        private val nextId = AtomicLong()

        fun create(kind: RouteGuidanceSourceKind): RouteGuidanceSource =
            RouteGuidanceSource(nextId.incrementAndGet(), kind)
    }
}

enum class NavigationClearReason {
    ROUTE_ENDED,
    SOURCE_REPLACED,
    SOURCE_CLOSED,
    SESSION_ENDED,
    STALE,
    SHUTDOWN,
}

data class NavigationPublication(
    val source: RouteGuidanceSource,
    val sessionGeneration: Long,
    val snapshot: AppleNavigationSnapshot,
)

data class NavigationClear(
    val source: RouteGuidanceSource?,
    val sessionGeneration: Long,
    val reason: NavigationClearReason,
)

/** Implementations must return quickly and move vendor I/O to their own bounded worker. */
interface NavigationOutput : Closeable {
    fun publish(publication: NavigationPublication)
    fun clear(clear: NavigationClear)
    override fun close() = Unit
}

/** Chooses one ready control source and permanently demotes bootstrap after a preferred source wins. */
class RouteGuidanceSourceSelector {
    private val ready = LinkedHashSet<RouteGuidanceSource>()
    private var preferredClaimed = false
    var owner: RouteGuidanceSource? = null
        private set

    @Synchronized
    fun opened(source: RouteGuidanceSource) {
        ready.remove(source)
    }

    @Synchronized
    fun ready(source: RouteGuidanceSource): Boolean {
        ready += source
        val previous = owner
        if (source.kind.preferred) {
            preferredClaimed = true
            owner = source
        } else if (!preferredClaimed && owner == null) {
            owner = source
        }
        return owner != previous
    }

    @Synchronized
    fun closed(source: RouteGuidanceSource): Boolean {
        ready -= source
        val previous = owner
        if (owner == source) owner = null
        if (owner == null && !preferredClaimed) {
            owner = ready.lastOrNull { !it.kind.preferred }
        }
        if (ready.isEmpty()) preferredClaimed = false
        return owner != previous
    }

    @Synchronized
    fun accepts(source: RouteGuidanceSource): Boolean = owner == source

    @Synchronized
    fun reset() {
        ready.clear()
        preferredClaimed = false
        owner = null
    }
}

/**
 * Owns one parser/store per iAP2 source and publishes only the selected source while a matching
 * CarPlay session is active. It never performs vendor calls itself.
 */
class RouteGuidanceCoordinator(
    outputs: List<NavigationOutput>,
    private val storeFactory: (RouteGuidanceSource) -> AppleRouteGuidanceStore = {
        AppleRouteGuidanceStore(generation = it.id)
    },
) : Closeable {
    private val lock = Any()
    private val outputs = outputs.toList()
    private val selector = RouteGuidanceSourceSelector()
    private val stores = LinkedHashMap<RouteGuidanceSource, AppleRouteGuidanceStore>()
    private var activeSession: Any? = null
    private var sessionGeneration = 0L
    private var publishedSource: RouteGuidanceSource? = null
    private var publishedRevision: Long? = null
    private var closed = false

    fun sourceOpened(source: RouteGuidanceSource) {
        synchronized(lock) {
            if (closed) return
            selector.opened(source)
            stores[source] = storeFactory(source)
        }
    }

    fun sourceReady(source: RouteGuidanceSource) {
        synchronized(lock) {
            if (closed || source !in stores) return
            val previous = selector.owner
            selector.ready(source)
            val actions = if (selector.owner == previous) emptyList() else ownerChangedActions(previous)
            dispatch(actions)
        }
    }

    fun onFrame(source: RouteGuidanceSource, frame: Iap2Frame) {
        if (frame.messageId != AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION
        ) return
        synchronized(lock) {
            if (closed) return
            val store = stores[source] ?: return
            val change = store.accept(frame.messageId, frame.payload)
            if (!selector.accepts(source) || activeSession == null) return
            val actions = when (change) {
                AppleRouteGuidanceChange.UPDATED -> store.current()?.let(::publishAction)?.let(::listOf).orEmpty()
                AppleRouteGuidanceChange.CLEARED -> listOf(clearAction(source, NavigationClearReason.ROUTE_ENDED))
                AppleRouteGuidanceChange.NONE -> emptyList()
            }
            dispatch(actions)
        }
    }

    fun sourceClosed(source: RouteGuidanceSource) {
        synchronized(lock) {
            if (closed) return
            val previous = selector.owner
            stores.remove(source)?.clear()
            selector.closed(source)
            if (previous != source) return
            val result = ArrayList<Dispatch>()
            result += clearAction(previous, NavigationClearReason.SOURCE_CLOSED)
            selector.owner?.let { owner -> stores[owner]?.current()?.let { result += publishAction(it) } }
            dispatch(result)
        }
    }

    fun sessionStarted(sessionToken: Any) {
        synchronized(lock) {
            if (closed || activeSession === sessionToken) return
            val replacing = activeSession != null
            activeSession = sessionToken
            sessionGeneration += 1
            val result = ArrayList<Dispatch>()
            if (replacing) {
                result += clearAction(selector.owner, NavigationClearReason.SESSION_ENDED)
                stores.values.forEach(AppleRouteGuidanceStore::clear)
            } else {
                selector.owner?.let { source -> stores[source]?.current()?.let { result += publishAction(it) } }
            }
            dispatch(result)
        }
    }

    fun sessionEnded(sessionToken: Any, replacementExpected: Boolean = false) {
        synchronized(lock) {
            if (closed || activeSession !== sessionToken) return
            activeSession = null
            stores.values.forEach(AppleRouteGuidanceStore::clear)
            sessionGeneration += 1
            dispatch(listOf(
                clearAction(
                    selector.owner,
                    if (replacementExpected) NavigationClearReason.SOURCE_REPLACED
                    else NavigationClearReason.SESSION_ENDED,
                ),
            ))
        }
    }

    /** Allows time-based stale and empty-list expiry to clear outputs without another frame. */
    fun refresh() {
        synchronized(lock) {
            if (closed || activeSession == null) return
            val source = selector.owner ?: return
            val snapshot = stores[source]?.current()
            val actions = when {
                snapshot == null && publishedSource != null ->
                    listOf(clearAction(source, NavigationClearReason.STALE))
                snapshot != null &&
                    (publishedSource != source || publishedRevision != snapshot.revision) ->
                    listOf(publishAction(snapshot))
                else -> emptyList()
            }
            dispatch(actions)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stores.values.forEach(AppleRouteGuidanceStore::clear)
            stores.clear()
            selector.reset()
            activeSession = null
            dispatch(listOf(clearAction(publishedSource, NavigationClearReason.SHUTDOWN), Dispatch.Close))
        }
    }

    private fun ownerChangedActions(previous: RouteGuidanceSource?): List<Dispatch> {
        if (activeSession == null) return emptyList()
        val result = ArrayList<Dispatch>()
        if (previous != null) result += clearAction(previous, NavigationClearReason.SOURCE_REPLACED)
        selector.owner?.let { source -> stores[source]?.current()?.let { result += publishAction(it) } }
        return result
    }

    private fun publishAction(snapshot: AppleNavigationSnapshot): Dispatch {
        val source = selector.owner ?: error("Navigation publication has no source owner")
        publishedSource = source
        publishedRevision = snapshot.revision
        return Dispatch.Publish(NavigationPublication(source, sessionGeneration, snapshot))
    }

    private fun clearAction(source: RouteGuidanceSource?, reason: NavigationClearReason): Dispatch {
        publishedSource = null
        publishedRevision = null
        return Dispatch.Clear(NavigationClear(source, sessionGeneration, reason))
    }

    private fun dispatch(actions: List<Dispatch>) {
        for (action in actions) {
            for (output in outputs) {
                runCatching {
                    when (action) {
                        is Dispatch.Publish -> output.publish(action.value)
                        is Dispatch.Clear -> output.clear(action.value)
                        Dispatch.Close -> output.close()
                    }
                }
            }
        }
    }

    private sealed interface Dispatch {
        data class Publish(val value: NavigationPublication) : Dispatch
        data class Clear(val value: NavigationClear) : Dispatch
        data object Close : Dispatch
    }
}
