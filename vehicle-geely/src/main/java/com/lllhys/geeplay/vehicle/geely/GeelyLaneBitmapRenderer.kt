package com.lllhys.geeplay.vehicle.geely

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import kotlin.math.cos
import kotlin.math.sin

/** Draws package-owned lane arrows without depending on OEM resource identifiers. */
class GeelyLaneBitmapRenderer(private val sizePixels: Int = DEFAULT_SIZE_PIXELS) {
    private data class Key(val sizePixels: Int, val lane: AppleLane, val night: Boolean)

    private val cache = object : LinkedHashMap<Key, Bitmap>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    init {
        require(sizePixels in MIN_SIZE_PIXELS..MAX_SIZE_PIXELS) {
            "Lane bitmap size must be in $MIN_SIZE_PIXELS..$MAX_SIZE_PIXELS"
        }
    }

    @Synchronized
    fun render(lane: AppleLane, night: Boolean): Bitmap =
        cache.getOrPut(Key(sizePixels, lane, night)) { draw(lane, night) }

    @Synchronized
    fun clear() {
        cache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        cache.clear()
    }

    private fun draw(lane: AppleLane, night: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val normal = when (lane.status) {
            AppleLaneStatus.NOT_GOOD -> if (night) Color.rgb(92, 99, 110) else Color.rgb(125, 132, 142)
            AppleLaneStatus.GOOD, AppleLaneStatus.PREFERRED -> if (night) Color.WHITE else Color.rgb(35, 42, 50)
        }
        val preferred = if (night) Color.rgb(70, 220, 130) else Color.rgb(0, 135, 75)
        val stemX = sizePixels / 2f
        val stemBottom = sizePixels * 0.88f
        val branchStart = sizePixels * 0.62f
        val branchLength = sizePixels * 0.46f
        val baseStroke = (sizePixels * 0.075f).coerceAtLeast(2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = normal
            strokeWidth = baseStroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        canvas.drawLine(stemX, stemBottom, stemX, branchStart, paint)
        val angles = lane.anglesDegrees.ifEmpty { listOf(0) }
        for (angle in angles) {
            val highlighted = lane.status == AppleLaneStatus.PREFERRED && lane.highlightAngleDegrees == angle
            paint.color = if (highlighted) preferred else normal
            paint.strokeWidth = if (highlighted) baseStroke * 1.3f else baseStroke
            val radians = Math.toRadians(angle.toDouble())
            val endX = stemX + sin(radians).toFloat() * branchLength
            val endY = branchStart - cos(radians).toFloat() * branchLength
            canvas.drawLine(stemX, branchStart, endX, endY, paint)
            drawArrowHead(canvas, paint, endX, endY, radians)
        }
        return bitmap
    }

    private fun drawArrowHead(canvas: Canvas, paint: Paint, x: Float, y: Float, radians: Double) {
        val head = sizePixels * 0.14f
        val backX = x - sin(radians).toFloat() * head
        val backY = y + cos(radians).toFloat() * head
        val sideX = cos(radians).toFloat() * head * 0.55f
        val sideY = sin(radians).toFloat() * head * 0.55f
        val path = Path().apply {
            moveTo(backX - sideX, backY - sideY)
            lineTo(x, y)
            lineTo(backX + sideX, backY + sideY)
        }
        canvas.drawPath(path, paint)
    }

    companion object {
        const val DEFAULT_SIZE_PIXELS = 96
        private const val MIN_SIZE_PIXELS = 32
        private const val MAX_SIZE_PIXELS = 512
        private const val MAX_CACHE_ENTRIES = 64
    }
}
