package com.lllhys.geeplay.vehicle.geely

import android.content.Context

enum class GeelyInputProfile {
    DISABLED,
    ONE_OS,
    FLYME_VOICE,
    ECARX_XUI_DIAGNOSTIC,
    ONE_OS_2_DIAGNOSTIC,
}

object GeelyInputSettings {
    private const val PREFERENCES = "geeplay_geely_input"
    private const val KEY_PROFILE = "input_profile"

    fun loadProfile(context: Context): GeelyInputProfile {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, null)
        return saved?.let { value -> GeelyInputProfile.entries.firstOrNull { it.name == value } }
            ?: GeelyInputProfile.DISABLED
    }

    fun saveProfile(context: Context, profile: GeelyInputProfile) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROFILE, profile.name)
                .commit(),
        ) { "Could not persist Geely input profile" }
    }
}
