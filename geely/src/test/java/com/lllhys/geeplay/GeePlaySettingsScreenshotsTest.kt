package com.lllhys.geeplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lllhys.geeplay.vehicle.geely.GeelyProductSettings
import com.lllhys.geeplay.vehicle.geely.R as GeelyR
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R as HostR
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeePlaySettingsScreenshotsTest {
    private val originalConfiguration = Configuration(RuntimeEnvironment.getApplication().resources.configuration)

    @After
    fun restoreConfiguration() {
        GeelyProductSettings.saveBlockLegacyBydMenus(RuntimeEnvironment.getApplication(), true)
        RuntimeEnvironment.setQualifiers("en")
        RuntimeEnvironment.getApplication().resources.updateConfiguration(
            originalConfiguration,
            RuntimeEnvironment.getApplication().resources.displayMetrics,
        )
    }

    @Test
    fun defaultBYDMenuBlockAppliesToAdvancedSettingsAndSearch() {
        val activity = openSettings("en", 1280, 1f, "ADVANCED")
        try {
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            layout(root, 1280)
            val visible = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
            assertTrue(activity.getString(GeelyR.string.settings_geely_block_byd_menus) in visible)
            assertFalse(activity.getString(HostR.string.carplay_map_on_instrument_cluster_experimental) in visible)
            assertFalse(activity.getString(HostR.string.settings_navigation_wheel_volume) in visible)
            assertFalse(activity.getString(HostR.string.settings_ambient_title) in visible)
            assertFalse(activity.getString(HostR.string.advanced_vehicle_data) in visible)

            @Suppress("UNCHECKED_CAST")
            val results = DiPlayActivity::class.java.getDeclaredMethod("buildSettingsSearchIndex")
                .apply { isAccessible = true }
                .invoke(activity) as List<Any>
            val titles = results.map { result ->
                result.javaClass.getDeclaredMethod("getTitle").invoke(result) as String
            }.toSet()
            assertTrue(activity.getString(GeelyR.string.settings_geely_block_byd_menus) in titles)
            listOf(
                HostR.string.carplay_map_on_instrument_cluster_experimental,
                HostR.string.settings_navigation_wheel_volume,
                HostR.string.settings_ambient_title,
                HostR.string.advanced_vehicle_data,
                HostR.string.byd_adb_features,
            ).forEach { blocked -> assertFalse(activity.getString(blocked) in titles) }
        } finally {
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun writesCompactFullAndArabicNavigationSettingsScreenshots() {
        listOf(
            Triple("en", 600, 1.3f),
            Triple("en", 1280, 1.5f),
            Triple("ar", 600, 1.3f),
        ).forEach { (locale, widthDp, fontScale) ->
            val activity = openSettings(locale, widthDp, fontScale, "NAVIGATION")
            try {
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                layout(root, widthDp)
                assertTrue(descendants(root).filterIsInstance<TextView>().any {
                    it.text == activity.getString(GeelyR.string.settings_geely_navigation_title)
                })
                assertFalse(descendants(root).filterIsInstance<TextView>().any {
                    it.text == activity.getString(
                        com.shilapi.xcertplay.host.R.string.settings_byd_navigation_unavailable,
                    )
                })
                writeScreenshot(root, "$locale-${widthDp}dp-font-$fontScale.png")
            } finally {
                activity.finish()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test
    fun writesCompactFullAndArabicEvccSettingsScreenshots() {
        listOf(
            Triple("en", 600, 1.3f),
            Triple("en", 1280, 1.5f),
            Triple("ar", 600, 1.3f),
        ).forEach { (locale, widthDp, fontScale) ->
            val activity = openSettings(locale, widthDp, fontScale, "ADVANCED")
            try {
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                layout(root, widthDp)
                val evccTitle = descendants(root).filterIsInstance<TextView>().firstOrNull {
                    it.text == activity.getString(GeelyR.string.settings_geely_evcc_title)
                }
                assertTrue(evccTitle != null)
                val evccCard = settingsCardFor(evccTitle!!)
                assertTrue(evccCard.width > 0 && evccCard.height > 0)
                writeScreenshot(evccCard, "evcc-$locale-${widthDp}dp-font-$fontScale.png")
            } finally {
                activity.finish()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test
    fun writesCompactFullAndArabicClusterDebugSettingsScreenshots() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
        val displayId = ShadowDisplayManager.addDisplay("w2880dp-h1080dp-mdpi", 5)
        shadowOf(manager.getDisplay(displayId)).apply {
            setName("Geely debug cluster")
            setFlags(Display.FLAG_PRESENTATION)
        }
        try {
            listOf(
                Triple("en", 600, 1.3f),
                Triple("en", 1280, 1.5f),
                Triple("ar", 600, 1.3f),
            ).forEach { (locale, widthDp, fontScale) ->
                val activity = openSettings(locale, widthDp, fontScale, "ADVANCED")
                try {
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    layout(root, widthDp)
                    val title = descendants(root).filterIsInstance<TextView>().firstOrNull {
                        it.text == activity.getString(GeelyR.string.settings_geely_cluster_display_title)
                    }
                    assertTrue(title != null)
                    val card = settingsCardFor(title!!)
                    assertTrue(card.width > 0 && card.height > 0)
                    assertTrue(descendants(card).filterIsInstance<TextView>().any {
                        it.text.toString().contains(
                            activity.getString(GeelyR.string.settings_geely_cluster_debug_target),
                        )
                    })
                    writeScreenshot(card, "cluster-debug-$locale-${widthDp}dp-font-$fontScale.png")
                } finally {
                    activity.finish()
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
        } finally {
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    private fun openSettings(
        locale: String,
        widthDp: Int,
        fontScale: Float,
        categoryName: String,
    ): DiPlayActivity {
        RuntimeEnvironment.setQualifiers("$locale-w${widthDp}dp-h960dp-mdpi")
        val app = RuntimeEnvironment.getApplication()
        app.resources.updateConfiguration(
            Configuration(app.resources.configuration).apply {
                this.fontScale = fontScale
                screenWidthDp = widthDp
                screenHeightDp = 960
            },
            app.resources.displayMetrics,
        )
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
        val categoryClass = Class.forName("com.shilapi.xcertplay.SettingsCategory") as Class<out Enum<*>>
        val category = categoryClass.enumConstants.single { it.name == categoryName }
        DiPlayActivity::class.java.getDeclaredField("settingsCategory").apply {
            isAccessible = true
            set(activity, category)
        }
        DiPlayActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun layout(root: View, widthDp: Int) {
        val density = root.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (960 * density).roundToInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun writeScreenshot(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val directory = Paths.get(System.getProperty("user.dir"), "build", "reports", "geely-settings")
            Files.createDirectories(directory)
            Files.newOutputStream(directory.resolve(name)).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun settingsCardFor(target: View): View =
        generateSequence(target.parent as? View) { it.parent as? View }
            .first { it is ViewGroup && it.background != null }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
