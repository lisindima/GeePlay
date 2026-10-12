package com.lllhys.geeplay.vehicle.geely

import android.content.Context
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.vehicle.VehicleSettingRow
import com.shilapi.xcertplay.vehicle.VehicleSettingsPlacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyVehicleSettingsControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun reset() {
        GeelyNavigationSettings.save(
            context,
            GeelyNavigationConfig(false, GeelyNavigationBackend.AUTO, false, false, 0),
        )
        GeelyMediaSettings.saveOwner(context, MediaOutputOwner.OEM_BLUETOOTH)
        GeelyInputSettings.saveProfile(context, GeelyInputProfile.DISABLED)
        GeelyProductSettings.saveBlockLegacyBydMenus(context, true)
    }

    @Test
    fun `cards cover navigation audio vehicle and diagnostics`() {
        val cards = GeelyVehicleSettingsController().cards(context)

        assertEquals(
            setOf(
                VehicleSettingsPlacement.NAVIGATION,
                VehicleSettingsPlacement.AUDIO,
                VehicleSettingsPlacement.VEHICLE,
                VehicleSettingsPlacement.DIAGNOSTICS,
                VehicleSettingsPlacement.ADVANCED,
            ),
            cards.map { it.placement }.toSet(),
        )
    }

    @Test
    fun `changing backend disables navigation until reconfirmed`() {
        GeelyNavigationSettings.save(
            context,
            GeelyNavigationConfig(true, GeelyNavigationBackend.AUTO, true, false, 0),
        )
        val backend = GeelyVehicleSettingsController().cards(context)
            .single { it.placement == VehicleSettingsPlacement.NAVIGATION }
            .rows.filterIsInstance<VehicleSettingRow.Choice>()
            .single { it.id == "geely_navigation_backend" }

        backend.save(GeelyNavigationBackend.ONE_OS.ordinal)

        val saved = GeelyNavigationSettings.load(context)
        assertEquals(GeelyNavigationBackend.ONE_OS, saved.backend)
        assertFalse(saved.enabled)
    }

    @Test
    fun `GeePlay defaults music ownership to factory Bluetooth`() {
        assertEquals(MediaOutputOwner.OEM_BLUETOOTH, GeelyMediaSettings.loadOwner(context))
        val row = GeelyVehicleSettingsController().cards(context)
            .single { it.placement == VehicleSettingsPlacement.AUDIO }
            .rows.filterIsInstance<VehicleSettingRow.Choice>()
            .single()
        assertEquals(0, row.selectedIndex)

        row.save(1)

        assertEquals(MediaOutputOwner.DIPLAY, GeelyMediaSettings.loadOwner(context))
    }

    @Test
    fun `legacy sample audio choice is preserved on upgrade`() {
        context.getSharedPreferences("geeplay_geely_media", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putBoolean("oem_bluetooth_music_enabled", false)
            .commit()

        assertEquals(MediaOutputOwner.DIPLAY, GeelyMediaSettings.loadOwner(context))
        assertEquals(
            MediaOutputOwner.DIPLAY.name,
            context.getSharedPreferences("geeplay_geely_media", Context.MODE_PRIVATE)
                .getString("media_output_owner", null),
        )
    }

    @Test
    fun `unvalidated physical display and junction rendering remain disabled`() {
        val cards = GeelyVehicleSettingsController().cards(context)
        val display = cards.single { it.id == "geely_cluster_display" }
            .rows.filterIsInstance<VehicleSettingRow.Toggle>()
            .single { it.id == "geely_cluster_display_enabled" }
        val junction = cards.single { it.placement == VehicleSettingsPlacement.NAVIGATION }
            .rows.filterIsInstance<VehicleSettingRow.Toggle>()
            .single { it.id == "geely_semantic_junction_enabled" }

        assertFalse(display.enabled)
        assertFalse(display.checked)
        assertFalse(junction.checked)
    }

    @Test
    fun `GeePlay blocks legacy BYD menus by default and refreshes settings when changed`() {
        val row = GeelyVehicleSettingsController().cards(context)
            .single { it.id == "geeplay_upstream_compatibility" }
            .rows.filterIsInstance<VehicleSettingRow.Toggle>()
            .single { it.id == "geeplay_block_legacy_byd_menus" }

        assertTrue(row.checked)
        assertEquals(com.shilapi.xcertplay.vehicle.VehicleSettingEffect.REFRESH_SETTINGS, row.effect)

        row.save(false)

        assertFalse(GeelyProductSettings.blockLegacyBydMenus(context))
    }

    @Test
    fun `input profile is a live setting while connection scoped choices request reconnect`() {
        val cards = GeelyVehicleSettingsController().cards(context)
        val input = cards.single { it.placement == VehicleSettingsPlacement.VEHICLE }
            .rows.filterIsInstance<VehicleSettingRow.Choice>()
            .single { it.id == "geely_input_profile" }
        val media = cards.single { it.placement == VehicleSettingsPlacement.AUDIO }
            .rows.filterIsInstance<VehicleSettingRow.Choice>()
            .single { it.id == "geely_media_owner" }

        assertEquals(com.shilapi.xcertplay.vehicle.VehicleSettingEffect.REFRESH_SETTINGS, input.effect)
        assertEquals(com.shilapi.xcertplay.vehicle.VehicleSettingEffect.NEXT_CONNECTION, media.effect)
    }

    @Test
    fun `all locales contain the complete Geely settings surface`() {
        val resourceRoot = java.io.File("src/main/res")
        val base = names(java.io.File(resourceRoot, "values/strings.xml"))
        val locales = resourceRoot.listFiles { file -> file.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?")) }!!

        assertTrue(locales.isNotEmpty())
        locales.forEach { locale -> assertEquals(locale.name, base, names(java.io.File(locale, "strings.xml"))) }
    }

    private fun names(file: java.io.File): Set<String> {
        val nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
    }
}
