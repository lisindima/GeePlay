package com.lllhys.geeplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.LocaleList
import com.lllhys.geeplay.vehicle.geely.GeelyClusterDisplaySettings
import com.shilapi.xcertplay.MapEmbedService
import com.shilapi.xcertplay.host.R as HostR
import com.shilapi.xcertplay.media.MediaOutputOwner
import com.shilapi.xcertplay.vehicle.VehicleSettingsPlacement
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeePlayApplicationTest {
    @Test
    fun `GeePlay product copy overrides visible DiPlay branding in every locale`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val productCopy = intArrayOf(
            HostR.string.setup_error_auth,
            HostR.string.make_diplay_feel_right_for_your_car,
            HostR.string.settings_language_summary,
            HostR.string.about_diplay,
            HostR.string.reports_save_to_downloads_diplay,
            HostR.string.in_carplay_swipe_down_with_three_fingers_to_open_diplay_se,
            HostR.string.diplay,
            HostR.string.settings_interface_size_hint,
        )

        listOf("en", "ar", "es", "ru", "uk", "zh-CN", "zh-TW").forEach { languageTag ->
            val localized = application.createConfigurationContext(
                Configuration(application.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
                },
            )
            productCopy.forEach { resource ->
                val value = localized.getString(resource)
                assertFalse("$languageTag must not show DiPlay: $value", value.contains("DiPlay"))
                assertTrue("$languageTag must show GeePlay: $value", value.contains("GeePlay"))
            }
        }
    }

    @Test
    fun `Geely product supplies identity policy and settings`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication

        assertEquals("GeePlay", application.productDisplayName(application))
        assertEquals("GEELY", application.defaultOemLabel(application))
        assertEquals("GEELY", com.shilapi.xcertplay.AirPlayPersistence.loadOemLabel(application))
        assertNotNull(application.defaultCarIconResource(application))
        assertEquals(
            "https://api.github.com/repos/lllhys/GeePlay/releases?per_page=3",
            application.updateReleasesUrl(application),
        )
        assertEquals(MediaOutputOwner.OEM_BLUETOOTH, application.mediaAudioPolicy(application).owner)
        assertFalse(application.legacyBydSettingsAvailable(application))
        assertEquals("GeePlay", application.diagnosticExportDirectoryName(application))
        assertEquals(
            setOf(
                VehicleSettingsPlacement.NAVIGATION,
                VehicleSettingsPlacement.AUDIO,
                VehicleSettingsPlacement.VEHICLE,
                VehicleSettingsPlacement.DIAGNOSTICS,
                VehicleSettingsPlacement.ADVANCED,
            ),
            application.vehicleSettings(application).cards(application).map { it.placement }.toSet(),
        )
    }

    @Test
    fun `legacy default BYD label migrates to GEELY without changing a custom label`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        com.shilapi.xcertplay.AirPlayPersistence.clearCustomAirPlayIcon(application)
        com.shilapi.xcertplay.AirPlayPersistence.saveOemLabel(application, "BYD")
        assertEquals("GEELY", com.shilapi.xcertplay.AirPlayPersistence.loadOemLabel(application))

        com.shilapi.xcertplay.AirPlayPersistence.saveOemLabel(application, "My Car")
        assertEquals("My Car", com.shilapi.xcertplay.AirPlayPersistence.loadOemLabel(application))
        com.shilapi.xcertplay.AirPlayPersistence.saveOemLabel(application, "BYD")
    }

    @Test
    fun `GeePlay does not expose launcher map embedding`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val component = ComponentName(application, com.shilapi.xcertplay.MapEmbedService::class.java)
        try {
            @Suppress("DEPRECATION")
            application.packageManager.getServiceInfo(component, 0)
            fail("MapEmbedService must be removed from the GeePlay manifest")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected: the common service is deliberately removed from this product.
        }

        assertNull(
            application.packageManager.resolveService(
                Intent(MapEmbedService.ACTION).setPackage(application.packageName),
                PackageManager.MATCH_DEFAULT_ONLY,
            ),
        )
        try {
            application.packageManager.getPermissionInfo("com.lllhys.geeplay.permission.NAVIGATION", 0)
            fail("The removed AutoDisplay signature permission must not remain in GeePlay")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected.
        }
        assertFalse(application.externalMapEmbeddingAvailable(application))
    }

    @Test
    fun `unvalidated physical display preference cannot request stream 111`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        GeelyClusterDisplaySettings.saveEnabled(application, true)
        try {
            assertEquals(false, application.requiresClusterStream(application))
        } finally {
            GeelyClusterDisplaySettings.saveEnabled(application, false)
        }
    }
}
