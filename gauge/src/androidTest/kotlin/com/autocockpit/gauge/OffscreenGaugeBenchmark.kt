package com.autocockpit.gauge

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autocockpit.gauge.render.GaugeRenderer
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offscreen-EGL benchmark: creates a pbuffer context, renders 1000 gauge frames (after 100 warmup
 * frames), serialises each frame with glFinish and reports GPU-side ms/frame. The JSON lands in the
 * target app's files dir so `tools/emulator.sh` can `adb pull` it into results/.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenGaugeBenchmark {

    @Test
    fun rendersOneThousandFramesOffscreen() {
        val egl = EglPbuffer(size = 1024)
        try {
            val renderer = GaugeRenderer()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 1024, 1024)
            assertTrue("gauge program failed to build: ${renderer.lastError}", renderer.programHandle != 0)

            repeat(WARMUP_FRAMES) { frame ->
                renderer.targetValue = sweep(frame, WARMUP_FRAMES)
                renderer.batteryValue = 0.4f + 0.5f * frame / WARMUP_FRAMES
                renderer.onDrawFrame(null)
            }
            GLES20.glFinish()

            val start = System.nanoTime()
            repeat(MEASURED_FRAMES) { frame ->
                renderer.targetValue = sweep(frame, MEASURED_FRAMES)
                renderer.batteryValue = 0.4f + 0.5f * frame / MEASURED_FRAMES
                renderer.onDrawFrame(null)
                GLES20.glFinish()
            }
            val totalNanos = System.nanoTime() - start
            val totalMs = totalNanos / 1_000_000.0
            val msPerFrame = totalMs / MEASURED_FRAMES

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val out = File(context.filesDir, "gauge_egl_benchmark.json")
            out.writeText(
                """
                {
                  "source": "OffscreenGaugeBenchmark",
                  "gpuSide": true,
                  "width": 1024,
                  "height": 1024,
                  "warmupFrames": $WARMUP_FRAMES,
                  "frames": $MEASURED_FRAMES,
                  "totalMs": ${round3(totalMs)},
                  "msPerFrame": ${round3(msPerFrame)},
                  "fps": ${round3(1000.0 / msPerFrame)},
                  "es3": ${renderer.useEs3},
                  "glVersion": "${GLES20.glGetString(GLES20.GL_VERSION)?.replace("\"", "")}",
                  "glRenderer": "${GLES20.glGetString(GLES20.GL_RENDERER)?.replace("\"", "")}"
                }
                """.trimIndent(),
            )
            assertTrue(out.exists())
            assertTrue("renderer drew no frames", renderer.frameCount >= WARMUP_FRAMES + MEASURED_FRAMES)
            println("offscreen EGL gauge: ${"%.3f".format(msPerFrame)} ms/frame over $MEASURED_FRAMES frames -> ${out.path}")
        } finally {
            egl.release()
        }
    }

    private fun sweep(index: Int, total: Int): Float {
        val phase = index.toFloat() / total
        return if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    }

    private fun round3(value: Double): String = String.format("%.3f", value)

    private class EglPbuffer(private val size: Int) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed 0x${Integer.toHexString(EGL14.eglGetError())}" }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0) {
                "no EGL config"
            }
            val config = configs[0]!!

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, size,
                EGL14.EGL_HEIGHT, size,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "no pbuffer surface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    companion object {
        private const val WARMUP_FRAMES = 100
        private const val MEASURED_FRAMES = 1_000
    }
}
