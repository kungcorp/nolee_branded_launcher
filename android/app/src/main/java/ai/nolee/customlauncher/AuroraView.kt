package ai.nolee.customlauncher

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** TextureView keeps the GPU aurora inside Compose's lens clipping and below the fading clock. */
internal class AuroraView(context: Context, private val onReady: () -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var entrance = 0f
    @Volatile var running = true
    private var renderer: Renderer? = null
    private var closed = false

    init {
        isOpaque = true
        isClickable = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        renderer = Renderer(surface, width, height).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.resize(width, height)
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // EGL must stop using the texture before it is released, on its owning thread.
        renderer?.stop(releaseTexture = true) ?: surface.release()
        renderer = null
        return false
    }
    fun close() {
        closed = true
        running = false
        renderer?.stop(releaseTexture = false)
    }

    private inner class Renderer(private val texture: SurfaceTexture, private var width: Int, private var height: Int) {
        private val thread = HandlerThread("NoleeAurora")
        private lateinit var handler: Handler
        private var display = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var readySent = false
        private var stopped = false
        // A fresh noise phase per visit, with coherent motion thereafter (not frame-to-frame jitter).
        private var elapsed = kotlin.random.Random.nextFloat() * 120f
        private var lastFrame = 0L
        private val uniforms = mutableMapOf<String, Int>()
        private val vertices = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, -1f,1f, 1f,-1f, 1f,1f)); position(0)
        }
        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post {
                try {
                    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
                    val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
                    val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                    val count = IntArray(1)
                    check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
                    eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(eglContext != EGL14.EGL_NO_CONTEXT)
                    surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0)
                    check(surface != EGL14.EGL_NO_SURFACE)
                    check(EGL14.eglMakeCurrent(display, surface, surface, eglContext))
                    val vertex = compile(GLES20.GL_VERTEX_SHADER, "attribute vec2 pos;void main(){gl_Position=vec4(pos,0.,1.);}")
                    val fragment = compile(GLES20.GL_FRAGMENT_SHADER, context.assets.open("aurora.frag").bufferedReader().use { it.readText() })
                    program = GLES20.glCreateProgram()
                    GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
                    GLES20.glLinkProgram(program)
                    val linked = IntArray(1)
                    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
                    check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
                    GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
                    GLES20.glUseProgram(program)
                    val at = GLES20.glGetAttribLocation(program, "pos")
                    GLES20.glEnableVertexAttribArray(at)
                    GLES20.glVertexAttribPointer(at, 2, GLES20.GL_FLOAT, false, 0, vertices)
                    listOf("res", "t", "life", "voice", "activity", "strength", "radius").forEach {
                        uniforms[it] = GLES20.glGetUniformLocation(program, it)
                    }
                    handler.post(frame)
                } catch (error: Exception) {
                    // Leave the settled clock visible if this device cannot initialise the effect.
                    Log.e("NoleeAurora", "Unable to start aurora", error)
                    cleanup()
                }
            }
        }
        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        fun resize(w: Int, h: Int) { handler.post { width = w; height = h } }
        private val frame: Runnable = object : Runnable {
            override fun run() {
                if (stopped) return
                val now = SystemClock.uptimeMillis()
                if (running && isShown) {
                    if (lastFrame != 0L) elapsed += (now - lastFrame) / 1000f
                    // Explicit viewport prevents the default 300 x 150 bottom-corner rendering bug.
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glUniform2f(uniforms.getValue("res"), width.toFloat(), height.toFloat())
                    fun uniform(name: String, value: Float) = GLES20.glUniform1f(uniforms.getValue(name), value)
                    uniform("t", elapsed)
                    uniform("life", auroraSmooth((entrance - .25f) / .75f))
                    uniform("radius", FULL_WATCH_OUTLINE_RADIUS / Design.WIDTH)
                    uniform("voice", .05f + .035f * kotlin.math.sin(elapsed * 1.5f))
                    uniform("activity", 0f)
                    uniform("strength", 1.25f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                    if (!EGL14.eglSwapBuffers(display, surface)) { cleanup(); return }
                    if (!readySent) {
                        readySent = true
                        post { if (!closed) onReady() }
                    }
                }
                // Do not advance while paused, or slow the animation clock on a slower GPU.
                lastFrame = if (running && isShown) now else 0L
                handler.postDelayed(this, if (running) 33L else 150L)
            }
        }
        fun stop(releaseTexture: Boolean) {
            handler.post {
                cleanup()
                if (releaseTexture) {
                    texture.release()
                    thread.quitSafely()
                }
            }
        }
        private fun cleanup() {
            if (stopped) return
            stopped = true
            handler.removeCallbacks(frame)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
        }
    }
}
