package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.kooo.evcc.evccapi.EvccCapability
import com.kooo.evcc.evccapi.EvccOperationResult
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPageRequest
import com.kooo.evcc.evccapi.EvccPropertyPage
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccSourceState
import com.kooo.evcc.evccapi.client.EvccApiClient
import java.io.Closeable

/** Read-only subset of the official EVCC client used by the vehicle-data adapter. */
internal interface EvccClientPort : Closeable {
    fun connect()
    fun disconnect()
    fun connectionState(): EvccApiClient.ConnectionState
    fun queryCapabilities(): List<EvccCapability>
    fun querySourceStates(): List<EvccSourceState>
    fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord
    fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord
    fun queryPropertyDirectory(request: EvccPageRequest): EvccPropertyPage
    fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult
}

internal class OfficialEvccClientPort(
    context: Context,
    listener: EvccApiClient.Listener,
) : EvccClientPort {
    private val delegate = EvccApiClient(context, listener = listener)

    override fun connect() = delegate.connect()
    override fun disconnect() = delegate.disconnect()
    override fun connectionState(): EvccApiClient.ConnectionState = delegate.connectionState()
    override fun queryCapabilities(): List<EvccCapability> = delegate.queryCapabilities()
    override fun querySourceStates(): List<EvccSourceState> = delegate.querySourceStates()
    override fun resolveProperty(selector: EvccPropertySelector): EvccPropertyRecord =
        delegate.resolveProperty(selector)

    override fun readProperty(selector: EvccPropertySelector, forceRefresh: Boolean): EvccPropertyRecord =
        delegate.readProperty(selector, forceRefresh)

    override fun queryPropertyDirectory(request: EvccPageRequest): EvccPropertyPage =
        delegate.queryPropertyDirectory(request)

    override fun replaceSubscriptions(selectors: List<EvccPropertySelector>): EvccOperationResult =
        delegate.replaceSubscriptions(selectors)

    override fun close() = delegate.close()
}
