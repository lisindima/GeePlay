package com.lllhys.geeplay.vehicle.geely

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Local layout editor and explicit setup action for the independent QNX backend. */
class GeelyQnxSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var host: EditText
    private lateinit var password: EditText
    private lateinit var binary: EditText
    private lateinit var z: EditText
    private lateinit var status: TextView
    private lateinit var preview: LayoutPreview
    private val positions = arrayOf(intArrayOf(640, 160, 640, 320), intArrayOf(1280, 160, 320, 160))
    private val enabled = mutableListOf<CheckBox>()
    private val geometry = mutableListOf<List<EditText>>()
    private var busy = false
    private var pendingChoice: CompletableFuture<String>? = null

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
        binary = field(R.string.settings_geely_qnx_binary, "")
        z = field(R.string.settings_geely_qnx_layer, "1", InputType.TYPE_CLASS_NUMBER)
        for (index in 0..1) {
            val saved = GeelyQnxSettings.load(this, index == 0)
            saved?.let { positions[index] = intArrayOf(it.x, it.y, it.width, it.height) }
            val check = CheckBox(this).apply {
                setText(if (index == 0) R.string.settings_geely_qnx_map else R.string.settings_geely_qnx_turns)
                isChecked = saved != null
            }
            enabled.add(check); form.addView(check)
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
        }
        preview = LayoutPreview().also { form.addView(it) }
        status = TextView(this).also { form.addView(it) }
        form.addView(Button(this).apply {
            setText(R.string.settings_geely_qnx_save)
            setOnClickListener { runCatching { save(); status.setText(R.string.settings_geely_qnx_saved) }
                .onFailure { status.setText(R.string.settings_geely_qnx_invalid) } }
        })
        form.addView(Button(this).apply {
            setText(R.string.settings_geely_qnx_start)
            setOnClickListener { startWindows() }
        })
        form.addView(Button(this).apply {
            setText(R.string.settings_geely_qnx_stop)
            setOnClickListener { startWindows(stop = true) }
        })
    }

    private fun updatePreview() {
        for (i in 0..1) for (n in 0..3) positions[i][n] = geometry[i][n].text.toString().toIntOrNull() ?: positions[i][n]
        preview.invalidate()
    }

    private fun layouts(): List<GeelyQnxLayout?> {
        updatePreview()
        return (0..1).map { i -> if (!enabled[i].isChecked) null else positions[i].let { p ->
            GeelyQnxLayout(GeelyQnxWindowConfig(host.text.toString().trim(), if (i == 0) 53281 else 53283, true), p[0], p[1], p[2], p[3])
        } }
    }

    private fun save() {
        val layouts = layouts()
        layouts.forEachIndexed { i, layout -> GeelyQnxSettings.save(this, i == 0, layout) }
    }

    private fun startWindows(stop: Boolean = false) {
        if (busy) return
        val layouts = if (stop) listOf(null, null) else runCatching { layouts() }.getOrElse { status.setText(R.string.settings_geely_qnx_invalid); return }
        val layer = z.text.toString().toIntOrNull()
        val source = binary.text.toString().trim()
        if (!stop && (layer == null || layer !in 0..8 || (source.isNotEmpty() && !Regex("/tmp/ghudbridgelited-dim-[A-Za-z0-9_-]+").matches(source)))) {
            status.setText(R.string.settings_geely_qnx_invalid); return
        }
        val address = host.text.toString().trim()
        if (address.isEmpty()) { status.setText(R.string.settings_geely_qnx_invalid); return }
        val secret = password.text.toString().toCharArray()
        password.text.clear()
        busy = true; status.setText(R.string.settings_geely_qnx_connecting)
        worker.execute {
            var stage = "connect"
            val result = runCatching {
                GeelyQnxShell.open(address, secret).use { shell ->
                    val controller = GeelyQnxDaemonController(shell)
                    val selectedSource = if (!stop && source.isEmpty()) selectSource(shell) else source
                    if (stop) {
                        stage = "stop"
                        controller.stop(53281); controller.stop(53283)
                    } else for (layout in layouts.filterNotNull()) {
                        stage = "stop:${layout.endpoint.port}"
                        controller.stop(layout.endpoint.port)
                        stage = "provision:${layout.endpoint.port}"
                        controller.provision(selectedSource, layout.endpoint.port)
                        stage = "start:${layout.endpoint.port}"
                        controller.start(layout.endpoint.port, requireNotNull(layer))
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
                layouts.forEachIndexed { i, layout -> GeelyQnxSettings.save(this, i == 0, if (stop) null else layout) }
            }
            secret.fill('\u0000')
            GeelyIntegrationDiagnostics.trace("QNX_SETUP", "stage=$stage result=${if (result.isSuccess) "ok" else result.exceptionOrNull()?.javaClass?.simpleName}")
            runOnUiThread { if (!isDestroyed) {
                busy = false
                if (stop && result.isSuccess) enabled.forEach { it.isChecked = false }
                status.setText(if (result.isSuccess) R.string.settings_geely_qnx_saved else R.string.settings_geely_qnx_failed)
                result.exceptionOrNull()?.let { status.append("\n$stage: ${it.message}") }
            } }
        }
    }

    private fun selectSource(shell: GeelyQnxShell): String {
        val candidates = shell.execute("ls /tmp/ghudbridgelited-dim-*").split(Regex("\\s+"))
            .filter { Regex("/tmp/ghudbridgelited-dim-[A-Za-z0-9_-]+").matches(it) }.distinct()
        check(candidates.isNotEmpty()) { "No installed AtlasDim daemon found" }
        if (candidates.size == 1) return candidates.single()
        val choice = CompletableFuture<String>()
        runOnUiThread {
            if (isDestroyed) choice.cancel(false) else {
                pendingChoice = choice
                AlertDialog.Builder(this).setTitle(R.string.settings_geely_qnx_binary)
                    .setItems(candidates.toTypedArray()) { _, index -> choice.complete(candidates[index]) }
                    .setOnCancelListener { choice.cancel(false) }.show()
            }
        }
        return choice.get(60, TimeUnit.SECONDS)
    }

    override fun onDestroy() { pendingChoice?.cancel(false); password.text.clear(); worker.shutdown(); super.onDestroy() }

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
                    selected = (1 downTo 0).firstOrNull { i -> positions[i].let { x >= it[0] && x <= it[0]+it[2] && y >= it[1] && y <= it[1]+it[3] } } ?: -1
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
