package com.lllhys.geeplay.vehicle.geely

import android.os.Build

/** Same connection-default policy as AtlasDim; a typed password overrides the build default. */
internal object GeelyQnxLogin {
    fun password(typed: String, builtIn: String = BuildConfig.QNX_PASSWORD): CharArray =
        typed.ifEmpty { builtIn }.toCharArray()

    fun display(model: String = Build.MODEL): Int = if (model == "G426_J1") 2 else 1
}
