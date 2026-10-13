package com.ecarx.eas.mapsdk.model.base

class LaneItem {
    var laneIndex: Int = -1
        private set
    var laneIconId: Int = -1
        private set

    fun setLaneIndex(value: Int): LaneItem = apply { laneIndex = value }
    fun setLaneIconId(value: Int): LaneItem = apply { laneIconId = value }
}
