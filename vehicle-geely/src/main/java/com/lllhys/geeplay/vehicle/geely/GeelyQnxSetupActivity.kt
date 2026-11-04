package com.lllhys.geeplay.vehicle.geely

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.*
import java.util.concurrent.Executors

/** Local layout editor and explicit setup action for the independent QNX backend. */
class GeelyQnxSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var host: EditText
    private lateinit var password: EditText
    private val displays = mutableListOf<EditText>()
    private val layers = mutableListOf<EditText>()
    private lateinit var status: TextView
    private lateinit var preview: LayoutPreview
    private val positions = arrayOf(intArrayOf(640, 160, 640, 320), intArrayOf(1280, 160, 320, 160))
    private val enabled = mutableListOf<CheckBox>()
    private val geometry = mutableListOf<List<EditText>>()
    private var busy = false
    private var connection: GeelyQnxShell? = null
    private var connectedHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_geely_qnx_title)
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val padding = (FORM_PADDING_DP * resources.displayMetrics.density).toInt()
        form.setPadding(padding, padding, padding, padding)
        scroll.addView(form); setContentView(scroll)
        fun label(id: Int) { form.addView(TextView(this).apply { setText(id) }) }
        fun field(id: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            label(id)
            return EditText(this).apply { inputType = type; setText(value); isSingleLine = true; form.addView(this) }
        }
        label(R.string.settings_geely_qnx_description)
        host = field(R.string.settings_geely_qnx_host, GeelyQnxSettings.load(this, true)?.endpoint?.host
            ?: GeelyQnxSettings.load(this, false)?.endpoint?.host ?: "192.168.118.2")
        password = field(R.string.settings_geely_qnx_password, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO }
        for (index in 0..1) {
            val saved = GeelyQnxSettings.load(this, index == 0)
            saved?.let { positions[index] = intArrayOf(it.x, it.y, it.width, it.height) }
            val check = CheckBox(this).apply {
                setText(if (index == 0) R.string.settings_geely_qnx_map else R.string.settings_geely_qnx_turns)
                isChecked = saved != null
            }
            enabled.add(check); form.addView(check)
            check.setOnCheckedChangeListener { _, _ -> if (::preview.isInitialized) preview.invalidate() }
            displays.add(field(R.string.settings_geely_qnx_display, (saved?.display ?: 2).toString(), InputType.TYPE_CLASS_NUMBER))
            layers.add(field(R.string.settings_geely_qnx_layer, (saved?.layer ?: 6).toString(), InputType.TYPE_CLASS_NUMBER))
            label(R.string.settings_geely_qnx_coordinates)
            val row = LinearLayout(this)
            val fields = positions[index].mapIndexed { coordinate, value ->
                EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = listOf("X", "Y", "W", "H")[coordinate]
                    contentDescription = hint
                    setText(value.toString())
                    row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    setOnFocusChangeListener { _, focused -> if (!focused) updatePreview() }
                }
            }
            geometry.add(fields); form.addView(row)
            form.addView(Button(this).apply {
                setText(if (index == 0) R.string.settings_geely_qnx_add_map else R.string.settings_geely_qnx_add_turns)
                setOnClickListener { enabled[index].isChecked = true; startWindows(selected = index) }
            })
            form.addView(Button(this).apply {
                setText(if (index == 0) R.string.settings_geely_qnx_remove_map else R.string.settings_geely_qnx_remove_turns)
                setOnClickListener { startWindows(stop = true, selected = index) }
            })
        }
        preview = LayoutPreview().also { form.addView(it) }
        status = TextView(this).also { form.addView(it) }
        form.addView(Button(this).apply {
            setText(R.string.settings_geely_qnx_save)
            setOnClickListener { runCatching { save(); status.setText(R.string.settings_geely_qnx_saved) }
                .onFailure { status.setText(R.string.settings_geely_qnx_invalid) } }
        })

    }

    private fun updatePreview() {
        for (i in geometry.indices) for (n in 0..3) positions[i][n] = geometry[i][n].text.toString().toIntOrNull() ?: positions[i][n]
        if (::preview.isInitialized) preview.invalidate()
    }

    private fun layouts(selected: Int? = null): List<GeelyQnxLayout?> {
        updatePreview()
        return (0..1).map { i -> if ((selected != null && selected != i) || !enabled[i].isChecked) null else positions[i].let { p ->
            GeelyQnxLayout(GeelyQnxWindowConfig(host.text.toString().trim(), if (i == 0) 53281 else 53283, true), p[0], p[1], p[2], p[3],
                displays[i].text.toString().toInt(), layers[i].text.toString().toInt())
        } }
    }

    private fun save() {
        val layouts = layouts()
        layouts.forEachIndexed { i, layout -> GeelyQnxSettings.save(this, i == 0, layout) }
    }

    private fun startWindows(stop: Boolean = false, selected: Int) {
        if (busy) return
        val layouts = if (stop) listOf(null, null) else runCatching { layouts(selected) }.getOrElse { status.setText(R.string.settings_geely_qnx_invalid); return }
        val address = host.text.toString().trim()
        if (address.isEmpty()) { status.setText(R.string.settings_geely_qnx_invalid); return }
        val secret = password.text.toString().toCharArray()
        password.text.clear()
        busy = true; status.setText(R.string.settings_geely_qnx_connecting)
        worker.execute {
            var stage = "connect"
            val result = runCatching {
                if (connectedHost != address) { connection?.close(); connection = null }
                val shell = connection ?: GeelyQnxShell.open(address, secret).also { connection = it; connectedHost = address }
                run {
                    val controller = GeelyQnxDaemonController(shell)
                    if (stop) {
                        stage = "stop"
                        controller.stop(if (selected == 0) 53281 else 53283)
                    } else for (layout in layouts.filterNotNull()) {
                        stage = "stop:${layout.endpoint.port}"
                        controller.stop(layout.endpoint.port)
                        stage = "provision:${layout.endpoint.port}"
                        val bytes = GeelyQnxBinary.load(this@GeelyQnxSetupActivity, layout.display)
                        controller.install(bytes, layout.endpoint.port) { sent, total ->
                            runOnUiThread { if (!isDestroyed) status.text = getString(R.string.settings_geely_qnx_uploading, sent * 100 / total) }
                        }
                        stage = "start:${layout.endpoint.port}"
                        controller.start(layout.endpoint.port, layout.layer)
                        stage = "verify:${layout.endpoint.port}"
                        var failure: Exception? = null
                        for (attempt in 0..9) {
                            try {
                                GeelyQnxConnection.open(layout.endpoint, layout.x, layout.y, layout.width, layout.height).use { it.ping() }
                                failure = null
                                break
                            } catch (error: Exception) { failure = error; Thread.sleep(200) }
                        }
                        failure?.let { throw it }
                    }
                }
                GeelyQnxSettings.save(this, selected == 0, if (stop) null else layouts[selected])
            }
            secret.fill('\u0000')
            if (result.isFailure) { runCatching { connection?.close() }; connection = null }
            GeelyIntegrationDiagnostics.trace("QNX_SETUP", "stage=$stage result=${if (result.isSuccess) "ok" else result.exceptionOrNull()?.javaClass?.simpleName}")
            runOnUiThread { if (!isDestroyed) {
                busy = false
                if (stop && result.isSuccess) enabled[selected].isChecked = false
                status.setText(if (result.isSuccess) R.string.settings_geely_qnx_saved else R.string.settings_geely_qnx_failed)
                result.exceptionOrNull()?.let { status.append("\n$stage: ${it.message}") }
            } }
        }
    }

    override fun onDestroy() {
        password.text.clear()
        worker.execute { runCatching { connection?.close() }; connection = null }
        worker.shutdown()
        super.onDestroy()
    }

    private inner class LayoutPreview : View(this@GeelyQnxSetupActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var selected = -1
        private var offsetX = 0f
        private var offsetY = 0f
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, (w * 532f / 1920).toInt())
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.DKGRAY)
            canvas.save(); canvas.scale(width / 1920f, height / 532f)
            for (i in 0..1) {
                if (!enabled[i].isChecked) continue
                val p = positions[i]
                paint.color = if (i == 0) Color.rgb(30, 100, 190) else Color.rgb(30, 150, 100)
                canvas.drawRect(p[0].toFloat(), p[1].toFloat(), (p[0]+p[2]).toFloat(), (p[1]+p[3]).toFloat(), paint)
                paint.color = Color.WHITE; paint.textSize = 32f
                canvas.drawText(getString(if (i == 0) R.string.settings_geely_qnx_map else R.string.settings_geely_qnx_turns), p[0]+8f, p[1]+40f, paint)
            }
            canvas.restore()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x * 1920 / width.coerceAtLeast(1)
            val y = event.y * 532 / height.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selected = (1 downTo 0).firstOrNull { i -> enabled[i].isChecked && positions[i].let { x >= it[0] && x <= it[0]+it[2] && y >= it[1] && y <= it[1]+it[3] } } ?: -1
                    if (selected < 0) return false
                    offsetX = x - positions[selected][0]; offsetY = y - positions[selected][1]
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (selected >= 0) {
                    val p = positions[selected]
                    p[0] = (x-offsetX).toInt().coerceIn(0, (1920-p[2]).coerceAtLeast(0))
                    p[1] = (y-offsetY).toInt().coerceIn(0, (532-p[3]).coerceAtLeast(0))
                    geometry[selected][0].setText(p[0].toString()); geometry[selected][1].setText(p[1].toString()); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { selected = -1; parent.requestDisallowInterceptTouchEvent(false); performClick() }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    companion object { private const val FORM_PADDING_DP = 16 }
}
