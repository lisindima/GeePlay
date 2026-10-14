package com.lllhys.geeplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.LocaleList
import android.view.Display
import com.lllhys.geeplay.vehicle.geely.GeelyClusterDisplaySettings
import com.lllhys.geeplay.vehicle.geely.GeelyProductSettings
import com.lllhys.geeplay.vehicle.geely.GeelyMediaSettings
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
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeePlayApplicationTest {
    @Test
    fun `GeePlay initializes unsaved music buffering to low latency without overriding a choice`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val preferences = application.getSharedPreferences("xcertplay_airplay", 0)
        preferences.edit().remove("media_buffer_ms").commit()
        try {
            application.initializeMediaBufferDefault(application)
            assertEquals(100, com.shilapi.xcertplay.AirPlayPersistence.loadMediaBufferMillis(application))

            com.shilapi.xcertplay.AirPlayPersistence.saveMediaBufferMillis(application, 500)
            application.initializeMediaBufferDefault(application)
            assertEquals(500, com.shilapi.xcertplay.AirPlayPersistence.loadMediaBufferMillis(application))
        } finally {
            preferences.edit().remove("media_buffer_ms").commit()
        }
    }

    @Test
    fun `GeePlay product copy overrides visible DiPlay branding in every locale`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val productCopy = intArrayOf(
            HostR.string.app_name,
            HostR.string.setup_error_auth,
            HostR.string.make_diplay_feel_right_for_your_car,
            HostR.string.settings_language_summary,
            HostR.string.about_diplay,
            HostR.string.reports_save_to_downloads_diplay,
            HostR.string.in_carplay_swipe_down_with_three_fingers_to_open_diplay_se,
            HostR.string.diplay,
            HostR.string.settings_interface_size_hint,
            HostR.string.backup_open_description,
            HostR.string.backup_import_invalid,
            HostR.string.settings_background_update_checks_description,
        )

        listOf("en", "ar", "es", "ja", "ru", "uk", "zh-CN", "zh-TW").forEach { languageTag ->
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
        assertNotNull(application.carPlayVehicleDataAdapter(application))
        assertFalse(application.legacyBydSettingsAvailable(application))
        assertFalse(application.embeddedGeelyWheelKeysAvailable(application))
        assertFalse(application.legacyPhoneAudioRouteAvailable(application))
        assertFalse(application.harmanFullscreenAvailable(application))
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
    fun `Bluetooth media arbitration is created only for confirmed CarPlay ownership`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        GeelyMediaSettings.saveOwner(application, MediaOutputOwner.OEM_BLUETOOTH)
        GeelyMediaSettings.saveBluetoothAutoAvoid(application, false)
        GeelyMediaSettings.saveBluetoothAutoRestore(application, false)
        try {
            assertSame(
                com.shilapi.xcertplay.vehicle.BluetoothAudioConflictPolicy.NONE,
                application.bluetoothAudioConflictPolicy(application),
            )

            GeelyMediaSettings.saveCarPlayOwnerWithAutoAvoid(application)
            val policy = application.bluetoothAudioConflictPolicy(application)
            assertFalse(policy === com.shilapi.xcertplay.vehicle.BluetoothAudioConflictPolicy.NONE)
            policy.close()
        } finally {
            GeelyMediaSettings.saveOwner(application, MediaOutputOwner.OEM_BLUETOOTH)
            GeelyMediaSettings.saveBluetoothAutoAvoid(application, false)
            GeelyMediaSettings.saveBluetoothAutoRestore(application, false)
        }
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
    fun `legacy BYD settings availability follows the GeePlay product switch`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        GeelyProductSettings.saveBlockLegacyBydMenus(application, true)
        try {
            assertFalse(application.legacyBydSettingsAvailable(application))
            GeelyProductSettings.saveBlockLegacyBydMenus(application, false)
            assertTrue(application.legacyBydSettingsAvailable(application))
        } finally {
            GeelyProductSettings.saveBlockLegacyBydMenus(application, true)
        }
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
    fun `GeePlay excludes the BYD Telecom audio route`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val component = ComponentName(
            application.packageName,
            "com.shilapi.xcertplay.telecom.PhoneAudioConnectionService",
        )
        try {
            @Suppress("DEPRECATION")
            application.packageManager.getServiceInfo(component, 0)
            fail("PhoneAudioConnectionService must be removed from the GeePlay manifest")
        } catch (_: PackageManager.NameNotFoundException) {
            // Expected: this service only drives BYD/DiLink's Telecom audio state.
        }
        @Suppress("DEPRECATION")
        val requestedPermissions = application.packageManager
            .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()
        assertFalse("android.permission.MANAGE_OWN_CALLS" in requestedPermissions)
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

    @Test
    fun `explicit debug display can request stream 111 without a validated profile`() {
        val application = RuntimeEnvironment.getApplication() as GeePlayApplication
        val manager = application.getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w2880dp-h1080dp-mdpi", 5)
        shadowOf(manager.getDisplay(displayId)).apply {
            setName("Geely debug cluster")
            setFlags(Display.FLAG_PRESENTATION)
        }
        GeelyClusterDisplaySettings.saveDebugDisplayId(application, displayId)
        try {
            assertTrue(application.requiresClusterStream(application))
            assertEquals(
                displayId,
                application.clusterDisplayPolicy(application)
                    .select(GeelyClusterDisplaySettings.debugCandidates(application))
                    ?.displayId,
            )
        } finally {
            GeelyClusterDisplaySettings.saveDebugDisplayId(application, null)
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }
}
