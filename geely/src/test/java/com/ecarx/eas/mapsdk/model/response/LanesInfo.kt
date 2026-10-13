package com.ecarx.eas.mapsdk.model.response

import com.ecarx.eas.mapsdk.model.base.LaneItem
import com.ecarx.sdk.navi.model.base.NaviBaseModel

class LanesInfo : NaviBaseModel() {
    var lanesEnabled: Boolean = false
        private set
    var laneItemList: List<LaneItem> = emptyList()
        private set

    fun setLanesEnabled(value: Boolean): LanesInfo = apply { lanesEnabled = value }
    fun setLaneItemList(value: List<LaneItem>): LanesInfo = apply { laneItemList = value }
}
