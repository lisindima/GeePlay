package com.ecarx.eas.sdk

abstract class ECarXApiClient {
    interface Callback {
        fun onAPIReady(ready: Boolean)
    }
}
