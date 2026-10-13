package com.ecarx.eas.mapsdk.interfaces

import com.ecarx.eas.mapsdk.model.response.LanesInfo
import com.ecarx.sdk.navi.model.base.NaviBaseModel

interface IBaseMapClient

interface IMapClient : IBaseMapClient {
    fun setMapEventListener(listener: IMapEventListener)
    fun invokeMapAPI(model: NaviBaseModel): NaviBaseModel
}

interface IMapEventListener {
    fun onMapStatusChanged(status: Int)
    fun onLanesInfoChanged(info: LanesInfo)
}
