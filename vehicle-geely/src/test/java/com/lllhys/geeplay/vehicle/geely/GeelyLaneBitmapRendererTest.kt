package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeelyLaneBitmapRendererTest {
    @Test
    fun `cache key includes lane state angles highlight night mode and size`() {
        val renderer = GeelyLaneBitmapRenderer(64)
        val lane = AppleLane(0, AppleLaneStatus.PREFERRED, listOf(0, 45), 45)

        assertSame(renderer.render(lane, false), renderer.render(lane, false))
        assertFalse(renderer.render(lane, false) === renderer.render(lane, true))
        assertFalse(renderer.render(lane, false) === GeelyLaneBitmapRenderer(96).render(lane, false))
    }

    @Test
    fun `preferred lane without a selected angle does not highlight every branch`() {
        val renderer = GeelyLaneBitmapRenderer(64)
        val good = renderer.render(AppleLane(0, AppleLaneStatus.GOOD, listOf(-45, 45), null), false)
        val preferredWithoutHighlight = renderer.render(
            AppleLane(0, AppleLaneStatus.PREFERRED, listOf(-45, 45), null),
            false,
        )
        val preferredWithHighlight = renderer.render(
            AppleLane(0, AppleLaneStatus.PREFERRED, listOf(-45, 45), 45),
            false,
        )

        assertArrayEquals(pixels(good), pixels(preferredWithoutHighlight))
        assertFalse(pixels(good).contentEquals(pixels(preferredWithHighlight)))
    }

    private fun pixels(bitmap: android.graphics.Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
}
