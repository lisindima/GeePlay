package com.lllhys.geeplay.vehicle.geely

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.vehicle.VehicleMapMirror
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Standard codec SurfaceTexture; GL scales the picture before bounded RGBA readback. */
class GeelyQnxMapSurface(config: GeelyQnxWindowConfig, x: Int, y: Int,
    private val width: Int, private val height: Int, private val streamWidth: Int, private val streamHeight: Int,
) : VehicleMapMirror {
    init {
        require(config.enabled && streamWidth in 1..4096 && streamHeight in 1..4096)
        GeelyQnxProtocol.hello(x,y,width,height)
    }
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("geeplay-qnx-map-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val output = GeelyQnxMapOutput(config, x, y, width, height)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var target = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var image: SurfaceTexture? = null
    private var destination: Surface? = null
    private var lastFrame = Long.MIN_VALUE / 2
    private val transform = FloatArray(16)
    private val pixels = ByteBuffer.allocateDirect(width * height * 4)
    private val viewport = mapViewport(width,height,streamWidth,streamHeight)
    private val vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    override val surface: Surface get() = requireNotNull(destination)

    init {
        val ready = CompletableFuture<Unit>()
        handler.post {
            try { initialize(); ready.complete(Unit) }
            catch (error: Exception) { releaseGl(); ready.completeExceptionally(error) }
        }
        try { ready.get(5, TimeUnit.SECONDS) }
        catch (error: Exception) { close(); throw IllegalStateException("QNX map GL initialization failed", error) }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        target = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, target, target, context))
        val id = IntArray(1); GLES20.glGenTextures(1, id, 0); texture = id[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; uniform mat4 transform; varying vec2 vUv; void main(){gl_Position=vec4(position,0.0,1.0);vUv=(transform*vec4(uv,0.0,1.0)).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES video; void main(){gl_FragColor=texture2D(video,vUv);}")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val linked = IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0); check(linked[0] != 0)
        image = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(streamWidth, streamHeight)
            it.setOnFrameAvailableListener({ source -> consume(source) }, handler)
            destination = Surface(it)
        }
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
        val compiled = IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,compiled,0)
        if (compiled[0] == 0) { GLES20.glDeleteShader(id); error("QNX map shader compilation failed") }
        return id
    }

    private fun consume(source: SurfaceTexture) {
        if (closed.get()) return
        try {
            source.updateTexImage() // Drain codec buffers even when this frame is skipped.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 200) return
            lastFrame = now
            source.getTransformMatrix(transform)
            GLES20.glClearColor(0f,0f,0f,0f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]); GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"video"),0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,transform,0)
            val position = GLES20.glGetAttribLocation(program,"position")
            val uv = GLES20.glGetAttribLocation(program,"uv")
            vertices.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices)
            vertices.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            pixels.clear(); GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            output.offer(topDownRgba(pixels,width,height))
        } catch (error: Exception) { GeelyQnxStatus.update("map", "capture_failed:${error.javaClass.simpleName}") }
    }

    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        output.close()
        handler.post { releaseGl(); thread.quitSafely() }
    }

    private fun releaseGl() {
        image?.setOnFrameAvailableListener(null)
        destination?.release(); destination = null; image?.release(); image = null
        if (context != EGL14.EGL_NO_CONTEXT) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (target != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,target)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
            EGL14.eglTerminate(display); EGL14.eglReleaseThread()
        }
        context = EGL14.EGL_NO_CONTEXT; target = EGL14.EGL_NO_SURFACE; display = EGL14.EGL_NO_DISPLAY
        program = 0; texture = 0
    }
}

/** GL readback starts at the bottom row; gcp/1 expects top-down RGBA. */
internal fun topDownRgba(source: ByteBuffer, width: Int, height: Int): ByteArray {
    require(width in 1..1920 && height in 1..532 && source.remaining() >= width * height * 4)
    val result = ByteArray(width * height * 4)
    val copy = source.duplicate()
    val start = source.position()
    for (row in 0 until height) {
        copy.position(start + (height - 1 - row) * width * 4)
        copy.get(result,row * width * 4,width * 4)
    }
    return result
}

/** Fit the navigation canvas without stretching road geometry or UI text. */
internal fun mapViewport(width: Int, height: Int, streamWidth: Int, streamHeight: Int): IntArray {
    require(width > 0 && height > 0 && streamWidth > 0 && streamHeight > 0)
    val scale = minOf(width.toDouble()/streamWidth, height.toDouble()/streamHeight)
    val w = (streamWidth*scale).toInt().coerceIn(1,width)
    val h = (streamHeight*scale).toInt().coerceIn(1,height)
    return intArrayOf((width-w)/2,(height-h)/2,w,h)
}
