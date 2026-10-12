package com.lllhys.geeplay.vehicle.geely

import android.content.Context

/** GeePlay product-boundary switches; defaults preserve a Geely-only settings surface. */
object GeelyProductSettings {
    private const val PREFERENCES = "geeplay_product"
    private const val KEY_BLOCK_LEGACY_BYD_MENUS = "block_legacy_byd_menus"

    fun blockLegacyBydMenus(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLOCK_LEGACY_BYD_MENUS, true)

    fun saveBlockLegacyBydMenus(context: Context, block: Boolean) {
        check(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BLOCK_LEGACY_BYD_MENUS, block)
                .commit(),
        ) { "Could not persist the GeePlay BYD-menu boundary" }
    }
}
