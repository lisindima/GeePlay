package com.lllhys.geeplay.vehicle.geely

object GeelyNavigationRuntimeControl {
    private val lock = Any()
    private var owner: Any? = null
    private var refresh: (() -> Unit)? = null
    private var disable: (() -> Unit)? = null

    fun attach(nextOwner: Any, refreshCallback: () -> Unit, disableCallback: () -> Unit) = synchronized(lock) {
        owner = nextOwner
        refresh = refreshCallback
        disable = disableCallback
    }

    fun detach(expectedOwner: Any) = synchronized(lock) {
        if (owner === expectedOwner) {
            owner = null
            refresh = null
            disable = null
        }
    }

    fun requestRefresh(): Boolean = synchronized(lock) { refresh }?.let {
        it()
        true
    } ?: false

    fun requestDisable(): Boolean = synchronized(lock) { disable }?.let {
        it()
        true
    } ?: false
}
