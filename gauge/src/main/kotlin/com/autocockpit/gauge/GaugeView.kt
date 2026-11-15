package com.autocockpit.gauge

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.autocockpit.gauge.render.FrameStats
import com.autocockpit.gauge.render.FrameTimeTracker
import com.autocockpit.gauge.render.GaugeRenderer
import java.io.File
import java.lang.ref.WeakReference

/**
 * GLSurfaceView hosting the cockpit gauge. Compose embeds it via AndroidView; values are pushed
 * from the ViewModel. Frame timing is recorded by a Choreographer-based [FrameTimeTracker].
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val gaugeRenderer = GaugeRenderer()

    // 2048 samples keeps the whole 1800-frame benchmark in the window.
    private val tracker = FrameTimeTracker(windowCapacity = 2048)

    val rendererForTest: GaugeRenderer get() = gaugeRenderer
    val trackerForTest: FrameTimeTracker get() = tracker

    init {
        setEGLContextClientVersion(if (supportsEs3(context)) 3 else 2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(gaugeRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
        lastCreated = WeakReference(this)
    }

    fun setValue(value: Float) {
        gaugeRenderer.targetValue = value.coerceIn(0f, 1f)
    }

    fun setBattery(value: Float) {
        gaugeRenderer.batteryValue = value.coerceIn(0f, 1f)
    }

    fun setDegraded(degraded: Boolean) {
        gaugeRenderer.degraded = degraded
    }

    fun frameStats(): FrameStats = tracker.snapshot()

    fun exportFrameMetrics(file: File): File = tracker.exportJson(file)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tracker.start()
    }

    override fun onDetachedFromWindow() {
        tracker.stop()
        super.onDetachedFromWindow()
    }

    companion object {
        @Volatile
        var lastCreated: WeakReference<GaugeView>? = null
            private set

        fun latest(): GaugeView? = lastCreated?.get()

        fun supportsEs3(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return (activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
        }
    }
}
