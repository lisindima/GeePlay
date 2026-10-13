package com.ecarx.eas.framework.sdk.navi

import android.content.Context
import com.ecarx.eas.mapsdk.interfaces.IBaseMapClient
import com.ecarx.eas.mapsdk.interfaces.IMapClient
import com.ecarx.eas.mapsdk.interfaces.IMapEventListener
import com.ecarx.eas.mapsdk.model.MapClientInfo
import com.ecarx.eas.mapsdk.model.response.LanesInfo
import com.ecarx.eas.sdk.ECarXApiClient
import com.ecarx.sdk.navi.model.base.NaviBaseModel

class MapAPI {
    val events = ArrayList<String>()
    val statuses = ArrayList<Int>()
    val lanes = ArrayList<LanesInfo>()
    var registered = false
    var unregistered = false
    var released = false
    var requestedMapVendor: Int? = null

    fun init(context: Context, callback: ECarXApiClient.Callback) {
        callback.onAPIReady(true)
    }

    fun registerMapClient(info: MapClientInfo, vararg clients: IBaseMapClient): NaviBaseModel {
        registered = true
        requestedMapVendor = info.mapVendor
        (clients.single() as IMapClient).setMapEventListener(object : IMapEventListener {
            override fun onMapStatusChanged(status: Int) {
                events += "status:$status"
                statuses += status
            }

            override fun onLanesInfoChanged(info: LanesInfo) {
                events += "lanes:${info.lanesEnabled}"
                lanes += info
            }
        })
        return NaviBaseModel().also {
            it.packageName = info.packageName
            // The real NaviService overwrites the caller's value from the package identity.
            it.mapVendor = registeredMapVendor
        }
    }

    fun unregisterMapClient(vararg clients: IBaseMapClient) {
        unregistered = clients.isNotEmpty()
    }

    fun release() {
        released = true
    }

    companion object {
        @JvmStatic
        var registeredMapVendor: Int = 31_415

        @JvmStatic
        var last: MapAPI? = null

        @JvmStatic
        fun get(context: Context): MapAPI = MapAPI().also { last = it }
    }
}
