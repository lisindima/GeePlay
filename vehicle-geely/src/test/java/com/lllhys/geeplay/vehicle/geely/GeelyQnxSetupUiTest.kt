package com.lllhys.geeplay.vehicle.geely

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.ScrollView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeelyQnxSetupUiTest {
    @Test fun editorRendersCompactFullAndRtlWithoutPersistingPassword() {
        for ((name, widthDp, language) in listOf(Triple("compact-ru", 320, "ru"), Triple("full-en", 960, "en"), Triple("compact-ar", 320, "ar"))) {
            val controller = Robolectric.buildActivity(GeelyQnxSetupActivity::class.java)
            val activity = controller.get()
            val locale = Locale.forLanguageTag(language)
            val config = Configuration(activity.resources.configuration).apply { fontScale = 1.5f; setLocale(locale); setLayoutDirection(locale) }
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            controller.create().start().resume().visible()
            try {
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val scroll = content.getChildAt(0) as ScrollView
                val form = scroll.getChildAt(0) as ViewGroup
                (0 until form.childCount).map { form.getChildAt(it) }.filterIsInstance<CheckBox>().forEach { it.isChecked = true }
                val fields = (0 until form.childCount).map { form.getChildAt(it) }.filterIsInstance<EditText>()
                assertFalse(fields[1].isSaveEnabled)
                assertEquals("", fields[1].text.toString())
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                form.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                form.layout(0, 0, width, form.measuredHeight)
                assertTrue(form.measuredHeight > 0)
                val bitmap = Bitmap.createBitmap(width, form.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    form.draw(Canvas(bitmap))
                    val directory = File("build/reports/qnx-ui").apply { mkdirs() }
                    FileOutputStream(File(directory, "$name.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            } finally { controller.pause().stop().destroy() }
        }
    }
}
