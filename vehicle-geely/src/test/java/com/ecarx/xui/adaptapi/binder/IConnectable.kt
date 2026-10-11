package com.ecarx.xui.adaptapi.binder

interface IConnectable {
    interface IConnectWatcher {
        fun onConnected()
        fun onDisConnected()
    }
}
