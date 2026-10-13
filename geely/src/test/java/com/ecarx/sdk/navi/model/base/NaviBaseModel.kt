package com.ecarx.sdk.navi.model.base

open class NaviBaseModel {
    var packageName: String = ""
    var mapVendor: Int = -1
    var errorCode: Int? = null

    fun copyBaseInfo(other: NaviBaseModel) {
        packageName = other.packageName
        mapVendor = other.mapVendor
    }
}
