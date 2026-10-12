package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.kooo.evcc.evccapi.EvccActionRequest
import com.kooo.evcc.evccapi.EvccCapability
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPageRequest
import com.kooo.evcc.evccapi.EvccPropertyPage
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccShellRequest
import com.kooo.evcc.evccapi.EvccSourceState
import com.kooo.evcc.evccapi.EvccWriteRequest
import com.kooo.evcc.evccapi.client.EvccApiClient
import java.io.Closeable

/** One process-level EVCC client shared by read and privileged control ports. */
class EvccConnectionCore(context: Context) : Closeable {
    private val client = EvccApiClient(context.applicationContext)
    private val owners = LinkedHashSet<Any>()
    private var closed = false

    internal fun readPort(listener: EvccApiClient.Listener): EvccClientPort =
        SharedEvccReadPort(this, listener)

    internal fun controlPort(listener: EvccApiClient.Listener): EvccControlPort =
        SharedEvccControlPort(this, listener)

    @Synchronized
    internal fun acquire(owner: Any) {
        if (closed) return
        val connect = owners.isEmpty()
        owners += owner
        if (connect) client.connect()
    }

    @Synchronized
    internal fun release(owner: Any) {
        owners -= owner
        if (!closed && owners.isEmpty()) client.disconnect()
    }

    internal fun addListener(listener: EvccApiClient.Listener) {
        client.addListener(listener)
        val info = client.currentApiInfo()
        val session = client.currentSessionInfo()
        if (info != null && session != null && client.connectionState() == EvccApiClient.ConnectionState.CONNECTED) {
            listener.onSessionReady(info, session)
        }
    }
    internal fun removeListener(listener: EvccApiClient.Listener) = client.removeListener(listener)
    internal fun state(): EvccApiClient.ConnectionState = client.connectionState()
    internal fun queryCapabilities(): List<EvccCapability> = client.queryCapabilities()
    internal fun querySourceStates(): List<EvccSourceState> = client.querySourceStates()
    internal fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord = client.resolveProperty(selector)
    internal fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord =
        client.readProperty(selector, forceRefresh)
    internal fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult =
        client.replaceSubscriptions(selectors)
    internal fun queryPropertyDirectory(request: EvccPageRequest): EvccPropertyPage =
        client.queryPropertyDirectory(request)
    internal fun writeProperty(request: EvccWriteRequest): EvccOperationResult = client.writeProperty(request)
    internal fun executeAction(request: EvccActionRequest): EvccOperationResult = client.executeAction(request)
    internal fun executeShell(request: EvccShellRequest): EvccOperationResult = client.executeShell(request)
    internal fun cancel(requestId: String): EvccOperationResult = client.cancel(requestId)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        owners.clear()
        client.close()
    }
}

private class SharedEvccReadPort(
    private val core: EvccConnectionCore,
    private val listener: EvccApiClient.Listener,
) : EvccClientPort {
    private val owner = Any()
    private var closed = false

    init { core.addListener(listener) }

    override fun connect() = core.acquire(owner)
    override fun disconnect() = core.release(owner)
    override fun connectionState() = core.state()
    override fun queryCapabilities() = core.queryCapabilities()
    override fun querySourceStates() = core.querySourceStates()
    override fun resolveProperty(selector: EvccPropertySelector) = core.resolveProperty(selector)
    override fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean) =
        core.readProperty(selector, forceRefresh)
    override fun replaceSubscriptions(selectors: List<EvccPropertySelector>) = core.replaceSubscriptions(selectors)
    override fun queryPropertyDirectory(request: EvccPageRequest) = core.queryPropertyDirectory(request)
    override fun close() {
        if (closed) return
        closed = true
        core.release(owner)
        core.removeListener(listener)
    }
}

internal interface EvccControlPort : Closeable {
    fun connect()
    fun disconnect()
    fun connectionState(): EvccApiClient.ConnectionState
    fun queryCapabilities(): List<EvccCapability>
    fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord
    fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord
    fun queryPropertyDirectory(request: EvccPageRequest): EvccPropertyPage
    fun writeProperty(request: EvccWriteRequest): EvccOperationResult
    fun executeAction(request: EvccActionRequest): EvccOperationResult
    fun executeShell(request: EvccShellRequest): EvccOperationResult
    fun cancel(requestId: String): EvccOperationResult
}

private class SharedEvccControlPort(
    private val core: EvccConnectionCore,
    private val listener: EvccApiClient.Listener,
) : EvccControlPort {
    private val owner = Any()
    private var closed = false

    init { core.addListener(listener) }

    override fun connect() = core.acquire(owner)
    override fun disconnect() = core.release(owner)
    override fun connectionState() = core.state()
    override fun queryCapabilities() = core.queryCapabilities()
    override fun resolveProperty(selector: EvccPropertySelector) = core.resolveProperty(selector)
    override fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean) =
        core.readProperty(selector, forceRefresh)
    override fun queryPropertyDirectory(request: EvccPageRequest) = core.queryPropertyDirectory(request)
    override fun writeProperty(request: EvccWriteRequest) = core.writeProperty(request)
    override fun executeAction(request: EvccActionRequest) = core.executeAction(request)
    override fun executeShell(request: EvccShellRequest) = core.executeShell(request)
    override fun cancel(requestId: String) = core.cancel(requestId)
    override fun close() {
        if (closed) return
        closed = true
        core.release(owner)
        core.removeListener(listener)
    }
}
