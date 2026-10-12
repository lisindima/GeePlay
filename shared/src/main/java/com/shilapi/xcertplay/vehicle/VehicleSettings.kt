package com.shilapi.xcertplay.vehicle

import android.content.Context

enum class VehicleSettingsPlacement {
    AUDIO,
    NAVIGATION,
    VEHICLE,
    DIAGNOSTICS,
    ADVANCED,
}

enum class VehicleSettingEffect {
    IMMEDIATE,
    REFRESH_SETTINGS,
    NEXT_CONNECTION,
}

data class VehicleSettingConfirmation(
    val title: String,
    val message: String,
    val positiveLabel: String,
)

sealed interface VehicleSettingRow {
    data class Information(val text: String) : VehicleSettingRow

    data class Toggle(
        val id: String,
        val title: String,
        val description: String,
        val checked: Boolean,
        val enabled: Boolean = true,
        val effect: VehicleSettingEffect = VehicleSettingEffect.IMMEDIATE,
        val enableConfirmation: VehicleSettingConfirmation? = null,
        val save: (Boolean) -> Unit,
    ) : VehicleSettingRow

    data class Choice(
        val id: String,
        val title: String,
        val options: List<String>,
        val selectedIndex: Int,
        val enabled: Boolean = true,
        val effect: VehicleSettingEffect = VehicleSettingEffect.NEXT_CONNECTION,
        val confirmations: Map<Int, VehicleSettingConfirmation> = emptyMap(),
        val save: (Int) -> Unit,
    ) : VehicleSettingRow {
        init {
            require(options.isNotEmpty()) { "Vehicle setting choices must not be empty" }
            require(selectedIndex in options.indices) { "Vehicle setting selection is out of range" }
        }
    }

    data class Action(
        val id: String,
        val title: String,
        val primary: Boolean = false,
        val confirmation: VehicleSettingConfirmation? = null,
        val run: () -> Unit,
    ) : VehicleSettingRow
}

data class VehicleSettingsCard(
    val id: String,
    val placement: VehicleSettingsPlacement,
    val title: String,
    val rows: List<VehicleSettingRow>,
)

fun interface VehicleSettingsController {
    fun cards(context: Context): List<VehicleSettingsCard>
}

class CompositeVehicleSettingsController(
    controllers: Iterable<VehicleSettingsController>,
) : VehicleSettingsController {
    private val delegates = controllers.toList()

    override fun cards(context: Context): List<VehicleSettingsCard> =
        delegates.flatMap { it.cards(context) }
}
