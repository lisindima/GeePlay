package com.lllhys.geeplay.vehicle.geely

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.vehicle.VehicleSettingConfirmation
import com.shilapi.xcertplay.vehicle.VehicleSettingEffect
import com.shilapi.xcertplay.vehicle.VehicleSettingRow
import com.shilapi.xcertplay.vehicle.VehicleSettingsCard
import com.shilapi.xcertplay.vehicle.VehicleSettingsController
import com.shilapi.xcertplay.vehicle.VehicleSettingsPlacement
import com.shilapi.xcertplay.vehicle.VehicleIntegrationProvider
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleDataAdapter
import com.lllhys.geeplay.vehicle.geely.evcc.EvccVehicleDataSettings

class GeelyVehicleSettingsController : VehicleSettingsController {
    override fun cards(context: Context): List<VehicleSettingsCard> {
        val navigation = GeelyNavigationSettings.load(context)
        val mediaOwner = GeelyMediaSettings.loadOwner(context)
        val inputProfile = GeelyInputSettings.loadProfile(context)
        val navigationAvailable = navigationSdkAvailable(context)
        val clusterPolicy = GeelyClusterDisplayPolicy()
        val clusterAvailable = clusterPolicy.hasValidatedFirmware()
        val clusterEnabled = GeelyClusterDisplaySettings.enabled(context)
        val clusterDebugDisplays = GeelyClusterDisplaySettings.debugCandidates(context)
        val clusterDebugDisplayId = GeelyClusterDisplaySettings.debugDisplayId(context)
        val clusterDebugSelectedIndex = clusterDebugDisplays
            .indexOfFirst { it.displayId == clusterDebugDisplayId }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: 0
        val status = GeelyNavigationStatusStore.snapshot()
        val evccAdapter = (context.applicationContext as? VehicleIntegrationProvider)
            ?.carPlayVehicleDataAdapter(context) as? EvccVehicleDataAdapter
        return listOf(
            VehicleSettingsCard(
                id = "geely_navigation",
                placement = VehicleSettingsPlacement.NAVIGATION,
                title = context.getString(R.string.settings_geely_navigation_title),
                rows = buildList {
                    add(VehicleSettingRow.Information(context.getString(R.string.settings_geely_navigation_description)))
                    add(
                        VehicleSettingRow.Choice(
                            id = "geely_navigation_backend",
                            title = context.getString(R.string.settings_geely_navigation_backend),
                            options = listOf(
                                context.getString(R.string.settings_geely_backend_auto),
                                context.getString(R.string.settings_geely_backend_ecarx_flyme),
                                context.getString(R.string.settings_geely_backend_oneos),
                            ),
                            selectedIndex = GeelyNavigationBackend.entries.indexOf(navigation.backend),
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            save = { index ->
                                GeelyNavigationRuntimeControl.requestDisable()
                                GeelyNavigationSettings.save(
                                    context,
                                    navigation.copy(enabled = false, backend = GeelyNavigationBackend.entries[index]),
                                )
                            },
                        ),
                    )
                    add(
                        VehicleSettingRow.Toggle(
                            id = "geely_navigation_enabled",
                            title = context.getString(R.string.settings_geely_navigation_enable),
                            description = context.getString(R.string.settings_geely_navigation_enable_description),
                            checked = navigation.enabled,
                            enabled = navigationAvailable,
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            enableConfirmation = VehicleSettingConfirmation(
                                title = context.getString(R.string.settings_geely_navigation_confirm_title),
                                message = context.getString(R.string.settings_geely_navigation_confirm_message),
                                positiveLabel = context.getString(R.string.settings_geely_navigation_confirm_action),
                            ),
                            save = { enabled ->
                                if (!enabled) GeelyNavigationRuntimeControl.requestDisable()
                                GeelyNavigationSettings.save(context, navigation.copy(enabled = enabled))
                            },
                        ),
                    )
                    add(
                        VehicleSettingRow.Toggle(
                            id = "geely_semantic_junction_enabled",
                            title = context.getString(R.string.settings_geely_semantic_junction),
                            description = context.getString(R.string.settings_geely_semantic_junction_description),
                            checked = navigation.semanticJunctionEnabled,
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            save = { enabled ->
                                GeelyNavigationSettings.save(context, navigation.copy(semanticJunctionEnabled = enabled))
                            },
                        ),
                    )
                    add(
                        VehicleSettingRow.Toggle(
                            id = "geely_lane_guidance_enabled",
                            title = context.getString(R.string.settings_geely_lane_guidance),
                            description = context.getString(R.string.settings_geely_lane_guidance_description),
                            checked = navigation.laneGuidanceEnabled,
                            enabled = navigationAvailable,
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            save = { enabled ->
                                GeelyNavigationSettings.save(context, navigation.copy(laneGuidanceEnabled = enabled))
                            },
                        ),
                    )
                    add(
                        VehicleSettingRow.Choice(
                            id = "geely_lane_number_base",
                            title = context.getString(R.string.settings_geely_lane_number_base),
                            options = listOf(
                                context.getString(R.string.settings_geely_lane_number_zero),
                                context.getString(R.string.settings_geely_lane_number_one),
                            ),
                            selectedIndex = navigation.laneNumberBase,
                            enabled = navigation.laneGuidanceEnabled,
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            save = { base ->
                                GeelyNavigationSettings.save(context, navigation.copy(laneNumberBase = base))
                            },
                        ),
                    )
                    if (navigation.backend == GeelyNavigationBackend.ONE_OS) {
                        add(
                            VehicleSettingRow.Toggle(
                                id = "geely_oneos_original_icons",
                                title = context.getString(R.string.settings_geely_oneos_original_icons),
                                description = context.getString(R.string.settings_geely_oneos_original_icons_description),
                                checked = navigation.originalOneOsIcons,
                                effect = VehicleSettingEffect.NEXT_CONNECTION,
                                save = { enabled ->
                                    GeelyNavigationSettings.save(context, navigation.copy(originalOneOsIcons = enabled))
                                },
                            ),
                        )
                    }
                },
            ),
            VehicleSettingsCard(
                id = "geely_evcc_vehicle_data",
                placement = VehicleSettingsPlacement.ADVANCED,
                title = context.getString(R.string.settings_geely_evcc_title),
                rows = listOf(
                    VehicleSettingRow.Information(
                        context.getString(R.string.settings_geely_evcc_description),
                    ),
                    VehicleSettingRow.Toggle(
                        id = "geely_evcc_vehicle_status",
                        title = context.getString(R.string.settings_geely_evcc_vehicle_status),
                        description = context.getString(R.string.settings_geely_evcc_vehicle_status_description),
                        checked = EvccVehicleDataSettings.vehicleStatusEnabled(context),
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        enableConfirmation = VehicleSettingConfirmation(
                            title = context.getString(R.string.settings_geely_evcc_confirm_title),
                            message = context.getString(R.string.settings_geely_evcc_confirm_message),
                            positiveLabel = context.getString(R.string.settings_geely_evcc_confirm_action),
                        ),
                        save = { enabled ->
                            EvccVehicleDataSettings.saveVehicleStatusEnabled(context, enabled)
                            evccAdapter?.prepare()
                        },
                    ),
                    VehicleSettingRow.Toggle(
                        id = "geely_evcc_vehicle_speed",
                        title = context.getString(R.string.settings_geely_evcc_vehicle_speed),
                        description = context.getString(R.string.settings_geely_evcc_vehicle_speed_description),
                        checked = EvccVehicleDataSettings.vehicleSpeedEnabled(context),
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        enableConfirmation = VehicleSettingConfirmation(
                            title = context.getString(R.string.settings_geely_evcc_confirm_title),
                            message = context.getString(R.string.settings_geely_evcc_confirm_message),
                            positiveLabel = context.getString(R.string.settings_geely_evcc_confirm_action),
                        ),
                        save = { enabled ->
                            EvccVehicleDataSettings.saveVehicleSpeedEnabled(context, enabled)
                            evccAdapter?.prepare()
                        },
                    ),
                    VehicleSettingRow.Choice(
                        id = "geely_evcc_low_charge_percent",
                        title = context.getString(R.string.settings_geely_evcc_low_charge_percent),
                        options = EvccVehicleDataSettings.lowChargePresets.map { "$it%" },
                        selectedIndex = EvccVehicleDataSettings.lowChargePresets
                            .indexOf(EvccVehicleDataSettings.lowChargePercent(context)),
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        save = { index ->
                            EvccVehicleDataSettings.saveLowChargePercent(
                                context,
                                EvccVehicleDataSettings.lowChargePresets[index],
                            )
                        },
                    ),
                    VehicleSettingRow.Choice(
                        id = "geely_evcc_charging_connectors",
                        title = context.getString(R.string.settings_geely_evcc_charging_connectors),
                        options = listOf("CCS2 / Type 2", "GB/T", "CCS1 / J1772"),
                        selectedIndex = when (EvccVehicleDataSettings.chargingConnectors(context)) {
                            EvChargingConnectors.CCS2_TYPE2 -> 0
                            EvChargingConnectors.GB_T -> 1
                            EvChargingConnectors.CCS1_J1772 -> 2
                        },
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        save = { index ->
                            EvccVehicleDataSettings.saveChargingConnectors(
                                context,
                                listOf(
                                    EvChargingConnectors.CCS2_TYPE2,
                                    EvChargingConnectors.GB_T,
                                    EvChargingConnectors.CCS1_J1772,
                                )[index],
                            )
                        },
                    ),
                    VehicleSettingRow.Action(
                        id = "geely_evcc_open_settings",
                        title = context.getString(R.string.settings_geely_evcc_open_settings),
                        run = { evccAdapter?.openEvccSettings() },
                    ),
                    VehicleSettingRow.Information(
                        evccAdapter?.diagnostics()
                            ?: context.getString(R.string.settings_geely_evcc_unavailable),
                    ),
                ),
            ),
            VehicleSettingsCard(
                id = "geely_audio",
                placement = VehicleSettingsPlacement.AUDIO,
                title = context.getString(R.string.settings_geely_audio_title),
                rows = listOf(
                    VehicleSettingRow.Information(context.getString(R.string.settings_geely_audio_description)),
                    VehicleSettingRow.Choice(
                        id = "geely_media_owner",
                        title = context.getString(R.string.settings_geely_media_owner),
                        options = listOf(
                            context.getString(R.string.settings_geely_media_owner_bluetooth),
                            context.getString(R.string.settings_geely_media_owner_diplay),
                        ),
                        selectedIndex = if (mediaOwner == MediaOutputOwner.DIPLAY) 1 else 0,
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        save = { index ->
                            GeelyMediaSettings.saveOwner(
                                context,
                                if (index == 1) MediaOutputOwner.DIPLAY else MediaOutputOwner.OEM_BLUETOOTH,
                            )
                        },
                    ),
                ),
            ),
            VehicleSettingsCard(
                id = "geely_input",
                placement = VehicleSettingsPlacement.VEHICLE,
                title = context.getString(R.string.settings_geely_input_title),
                rows = listOf(
                    VehicleSettingRow.Information(context.getString(R.string.settings_geely_input_description)),
                    VehicleSettingRow.Choice(
                        id = "geely_input_profile",
                        title = context.getString(R.string.settings_geely_input_profile),
                        options = listOf(
                            context.getString(R.string.settings_geely_input_disabled),
                            context.getString(R.string.settings_geely_input_oneos),
                            context.getString(R.string.settings_geely_input_flyme),
                            context.getString(R.string.settings_geely_input_xui_diagnostic),
                            context.getString(R.string.settings_geely_input_oneos2_diagnostic),
                        ),
                        selectedIndex = GeelyInputProfile.entries.indexOf(inputProfile),
                        effect = VehicleSettingEffect.REFRESH_SETTINGS,
                        confirmations = mapOf(
                            GeelyInputProfile.FLYME_VOICE.ordinal to VehicleSettingConfirmation(
                                title = context.getString(R.string.settings_geely_flyme_confirm_title),
                                message = context.getString(R.string.settings_geely_flyme_confirm_message),
                                positiveLabel = context.getString(R.string.settings_geely_flyme_confirm_action),
                            ),
                        ),
                        save = { index -> GeelyInputSettings.saveProfile(context, GeelyInputProfile.entries[index]) },
                    ),
                ),
            ),
            VehicleSettingsCard(
                id = "geely_cluster_display",
                placement = VehicleSettingsPlacement.ADVANCED,
                title = context.getString(R.string.settings_geely_cluster_display_title),
                rows = buildList {
                    add(VehicleSettingRow.Information(
                        context.getString(
                            if (clusterAvailable) R.string.settings_geely_cluster_display_description
                            else R.string.settings_geely_cluster_display_unavailable,
                        ),
                    ))
                    add(VehicleSettingRow.Toggle(
                        id = "geely_cluster_display_enabled",
                        title = context.getString(R.string.settings_geely_cluster_display_enable),
                        description = context.getString(R.string.settings_geely_cluster_display_description),
                        checked = clusterEnabled && clusterAvailable,
                        enabled = clusterAvailable,
                        effect = VehicleSettingEffect.NEXT_CONNECTION,
                        save = { enabled -> GeelyClusterDisplaySettings.saveEnabled(context, enabled) },
                    ))
                    add(
                        VehicleSettingRow.Information(
                            context.getString(
                                if (clusterDebugDisplays.isEmpty()) {
                                    R.string.settings_geely_cluster_debug_no_displays
                                } else {
                                    R.string.settings_geely_cluster_debug_description
                                },
                            ),
                        ),
                    )
                    val debugConfirmation = VehicleSettingConfirmation(
                        title = context.getString(R.string.settings_geely_cluster_debug_confirm_title),
                        message = context.getString(R.string.settings_geely_cluster_debug_confirm_message),
                        positiveLabel = context.getString(R.string.settings_geely_cluster_debug_confirm_action),
                    )
                    add(
                        VehicleSettingRow.Choice(
                            id = "geely_cluster_debug_display",
                            title = context.getString(R.string.settings_geely_cluster_debug_target),
                            options = listOf(context.getString(R.string.settings_geely_cluster_debug_disabled)) +
                                clusterDebugDisplays.map { display ->
                                    "${display.displayId} · ${display.name} · ${display.width}×${display.height}"
                                },
                            selectedIndex = clusterDebugSelectedIndex,
                            enabled = clusterDebugDisplays.isNotEmpty() || clusterDebugDisplayId != null,
                            effect = VehicleSettingEffect.NEXT_CONNECTION,
                            confirmations = clusterDebugDisplays.indices.associate { index ->
                                index + 1 to debugConfirmation
                            },
                            save = { index ->
                                GeelyClusterDisplaySettings.saveDebugDisplayId(
                                    context,
                                    clusterDebugDisplays.getOrNull(index - 1)?.displayId,
                                )
                            },
                        ),
                    )
                },
            ),
            VehicleSettingsCard(
                id = "geely_diagnostics",
                placement = VehicleSettingsPlacement.DIAGNOSTICS,
                title = context.getString(R.string.settings_geely_diagnostics_title),
                rows = listOf(
                    VehicleSettingRow.Information(
                        context.getString(
                            R.string.settings_geely_diagnostics_status,
                            if (navigationAvailable) {
                                context.getString(R.string.settings_geely_available)
                            } else {
                                context.getString(R.string.settings_geely_unavailable)
                            },
                            status.state.name,
                            status.failureType ?: "-",
                        ),
                    ),
                    VehicleSettingRow.Information(GeelyIntegrationDiagnostics.report(context)),
                    VehicleSettingRow.Action(
                        id = "geely_refresh",
                        title = context.getString(R.string.settings_geely_refresh),
                        run = {
                            if (!GeelyNavigationRuntimeControl.requestRefresh()) {
                                GeelyNavigationRecovery.restoreOnAppOpen(context)
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private fun navigationSdkAvailable(context: Context): Boolean = runCatching {
        Class.forName(GeelyDimNavigationAdapter.DIM_INTERACTION, false, context.classLoader)
        Class.forName(GeelyDimNavigationAdapter.NAVIGATION_INFORMATION, false, context.classLoader)
    }.isSuccess

    fun oneOsServiceAvailable(context: Context): Boolean =
        context.packageManager.resolveService(
            Intent().setComponent(
                ComponentName(
                    "com.geely.service.oneosapi",
                    "com.geely.service.oneosapi.OneOSApiService",
                ),
            ),
            0,
        ) != null
}
