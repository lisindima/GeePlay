package com.ecarx.xui.adaptapi.diminteraction

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import com.ecarx.xui.adaptapi.binder.IConnectable

interface INaviInteraction {
    interface INavigationInfo {
        fun getDayNightMode(): Int
        fun getDistanceToDestination(): Long
        fun getDistanceToNextGuidancePoint(): Long
        fun getDrivingDirection(): Int
        fun getETA(): Long
        fun getNavigationStatus(): Int
        fun getNavigationTurnId(): Int
        fun getMuteState(): Int
        fun getNextGuidancePointName(): String
        fun getExtensionInfo(): Bundle
        fun getLaneInfo(): Array<ILaneInfo>
        fun getNavigationTurnSVG(): String?
    }

    interface ILaneInfo {
        fun getLaneBackIcon(): Bitmap
        fun getLaneBackIconId(): Int
        fun getLaneNumber(): Int
    }

    interface INavigationInteractionCallback {
        fun onNavigationStatusChanged(status: Int)
    }
}

class DimInteraction private constructor() {
    val calls = ArrayList<String>()
    val navigation = RecordingNavigation()
    private var watcher: IConnectable.IConnectWatcher? = null
    var rejectedMethod: String? = null
    var afterCall: ((String) -> Unit)? = null

    fun getNaviInteraction(): INaviInteraction {
        calls += "getNaviInteraction"
        return navigation
    }

    fun setPresentationToDimSwitch(type: Int, packageName: String, activity: String, enabled: Boolean): Int {
        calls += "switch:$type:$packageName:$activity:$enabled"
        return result("switch")
    }

    fun registerConnectWatcher(value: IConnectable.IConnectWatcher): Int {
        calls += "registerWatcher"
        watcher = value
        return result("registerWatcher")
    }

    fun unregisterConnectWatcher(value: IConnectable.IConnectWatcher): Int {
        calls += "unregisterWatcher"
        if (watcher === value) watcher = null
        return result("unregisterWatcher")
    }

    fun connect(): Int {
        calls += "connect"
        watcher?.onConnected()
        return result("connect")
    }

    fun disconnect(): Int {
        calls += "disconnect"
        return result("disconnect")
    }

    private fun result(method: String): Int {
        val result = if (rejectedMethod == method) -1 else 0
        afterCall?.invoke(method)
        return result
    }

    class RecordingNavigation : INaviInteraction {
        val calls = ArrayList<String>()
        var lastStatus = -1
        var lastTurnId = -1
        var lastNextDistance = -1L
        var lastDestination = -1L
        var lastEta = -1L
        var lastRoad = ""
        var lastLanes = emptyList<Lane>()
        private var callback: INaviInteraction.INavigationInteractionCallback? = null

        data class Lane(val number: Int, val iconId: Int, val bitmap: Bitmap)

        fun updateNavigationInfo(info: INaviInteraction.INavigationInfo): Int {
            calls += "info"
            lastStatus = info.getNavigationStatus()
            lastTurnId = info.getNavigationTurnId()
            lastNextDistance = info.getDistanceToNextGuidancePoint()
            lastDestination = info.getDistanceToDestination()
            lastEta = info.getETA()
            lastRoad = info.getNextGuidancePointName()
            lastLanes = info.getLaneInfo().map {
                Lane(it.getLaneNumber(), it.getLaneBackIconId(), it.getLaneBackIcon())
            }
            return 0
        }

        fun updateLaneInfo(lanes: Array<INaviInteraction.ILaneInfo>): Int {
            calls += "lanes"
            lastLanes = lanes.map { Lane(it.getLaneNumber(), it.getLaneBackIconId(), it.getLaneBackIcon()) }
            return 0
        }

        fun notifyNavigationStatus(status: Int): Int { calls += "status:$status"; return 0 }
        fun notifyTurnByTurnStarted(): Int { calls += "started"; return 0 }
        fun notifyTurnByTurnStopped(): Int { calls += "stopped"; return 0 }
        fun updateDistanceToDestination(value: Long): Int { calls += "destination:$value"; return 0 }
        fun updateDistanceToNextGuidancePoint(value: Long): Int { calls += "next:$value"; return 0 }
        fun updateTurnByTurnArrow(value: Int): Int { calls += "arrow:$value"; return 0 }
        fun updateETA(value: Long): Int { calls += "eta:$value"; return 0 }
        fun updateNextGuidancePointName(value: String): Int { calls += "road:$value"; return 0 }
        fun registerNavigationInteractionCallback(value: INaviInteraction.INavigationInteractionCallback): Int {
            calls += "registerCallback"
            callback = value
            return 0
        }
        fun unregisterNavigationInteractionCallback(value: INaviInteraction.INavigationInteractionCallback): Int {
            calls += "unregisterCallback"
            if (callback === value) callback = null
            return 0
        }
    }

    companion object {
        @JvmStatic var latest: DimInteraction? = null

        @JvmStatic
        fun create(context: Context): DimInteraction = DimInteraction().also { latest = it }
    }
}
