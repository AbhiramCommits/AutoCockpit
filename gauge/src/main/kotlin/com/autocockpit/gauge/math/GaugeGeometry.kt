package com.autocockpit.gauge.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Vec2(val x: Float, val y: Float)

data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    companion object {
        val ACTIVE = Rgba(0.20f, 0.88f, 0.72f)
        val CONSUMPTION = Rgba(1.00f, 0.42f, 0.28f)
        val INACTIVE = Rgba(0.14f, 0.19f, 0.24f)
        val TICK = Rgba(0.62f, 0.72f, 0.80f)
        val ENERGY = Rgba(0.35f, 0.80f, 1.00f)
    }
}

/**
 * Geometry description for an arc gauge. Angles are in degrees, measured clockwise from the
 * positive X axis with Y pointing down (screen space), so the default 135°..405° sweep draws the
 * classic bottom-left-to-bottom-right dial.
 */
data class ArcGaugeSpec(
    val startAngleDegrees: Float = 135f,
    val sweepDegrees: Float = 270f,
    val innerRadius: Float = 0.60f,
    val outerRadius: Float = 0.88f,
    val segments: Int = 128,
) {
    init {
        require(segments in 8..4096) { "segments out of range: $segments" }
        require(innerRadius in 0f..outerRadius) { "innerRadius must be <= outerRadius" }
        require(outerRadius <= 1f) { "outerRadius must fit the unit quad" }
        require(sweepDegrees > 0f && sweepDegrees <= 360f) { "sweepDegrees out of range: $sweepDegrees" }
    }

    val vertexCount: Int get() = (segments + 1) * 2

    fun angleForValue(value: Float): Float =
        startAngleDegrees + sweepDegrees * value.coerceIn(0f, 1f)

    fun pointAt(radius: Float, angleDegrees: Float): Vec2 {
        val radians = angleDegrees * PI.toFloat() / 180f
        return Vec2(radius * cos(radians), radius * sin(radians))
    }

    companion object {
        const val FLOATS_PER_VERTEX: Int = 7 // position xy, rgba, normalized value
    }
}

/**
 * Preallocated interleaved vertex buffer for the arc strip:
 * `[x, y, r, g, b, a, value]` per vertex, two vertices per segment boundary.
 *
 * The buffer is allocated once; [update] rewrites it in place and always returns the same array,
 * so the renderer performs no per-frame allocation.
 */
class GaugeMesh(val spec: ArcGaugeSpec = ArcGaugeSpec()) {
    private val data = FloatArray(spec.vertexCount * ArcGaugeSpec.FLOATS_PER_VERTEX)

    var allocationCount: Int = 0
        private set
    var updateCount: Int = 0
        private set

    fun buffer(): FloatArray = data

    fun vertexCount(): Int = spec.vertexCount

    fun update(
        value: Float,
        activeColor: Rgba = Rgba.ACTIVE,
        inactiveColor: Rgba = Rgba.INACTIVE,
    ): FloatArray {
        val clamped = value.coerceIn(0f, 1f)
        var offset = 0
        for (i in 0..spec.segments) {
            val t = i.toFloat() / spec.segments
            val angle = spec.startAngleDegrees + spec.sweepDegrees * t
            val inner = spec.pointAt(spec.innerRadius, angle)
            val outer = spec.pointAt(spec.outerRadius, angle)
            val filled = clamped > 0f && t <= clamped
            val color = if (filled) activeColor else inactiveColor
            offset = writeVertex(offset, inner, color, t)
            offset = writeVertex(offset, outer, color, t)
        }
        updateCount++
        return data
    }

    private fun writeVertex(offset: Int, point: Vec2, color: Rgba, value: Float): Int {
        var i = offset
        data[i++] = point.x
        data[i++] = point.y
        data[i++] = color.r
        data[i++] = color.g
        data[i++] = color.b
        data[i++] = color.a
        data[i++] = value
        return i
    }

    companion object {
        /** Allocates a mesh and records the single allocation that is allowed per gauge. */
        fun create(spec: ArcGaugeSpec = ArcGaugeSpec()): GaugeMesh = GaugeMesh(spec).also { it.allocationCount = 1 }
    }
}

/** Tick marks rendered as GL_LINES: `[x, y, r, g, b, a]` per vertex, two vertices per tick. */
object TickMarks {
    const val FLOATS_PER_VERTEX: Int = 6

    fun build(spec: ArcGaugeSpec, tickCount: Int, majorEvery: Int = 5): FloatArray {
        require(tickCount >= 2) { "tickCount must be >= 2" }
        require(majorEvery >= 1) { "majorEvery must be >= 1" }
        val data = FloatArray(tickCount * 2 * FLOATS_PER_VERTEX)
        var offset = 0
        for (i in 0 until tickCount) {
            val t = i.toFloat() / (tickCount - 1)
            val angle = spec.startAngleDegrees + spec.sweepDegrees * t
            val major = i % majorEvery == 0
            val startRadius = if (major) spec.outerRadius + 0.02f else spec.outerRadius + 0.008f
            val endRadius = spec.outerRadius + if (major) 0.10f else 0.05f
            val color = if (major) Rgba.TICK else Rgba(0.42f, 0.50f, 0.58f)
            val inner = spec.pointAt(startRadius, angle)
            val outer = spec.pointAt(endRadius, angle)
            offset = write(data, offset, inner, color)
            offset = write(data, offset, outer, color)
        }
        return data
    }

    fun vertexCount(tickCount: Int): Int = tickCount * 2

    private fun write(data: FloatArray, offset: Int, point: Vec2, color: Rgba): Int {
        var i = offset
        data[i++] = point.x
        data[i++] = point.y
        data[i++] = color.r
        data[i++] = color.g
        data[i++] = color.b
        data[i++] = color.a
        return i
    }
}

/** Battery/energy bar as a triangle list (two triangles, six vertices). */
data class BarRect(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
) {
    init {
        require(right > left) { "right must exceed left" }
        require(top > bottom) { "top must exceed bottom" }
    }

    companion object {
        val DEFAULT = BarRect(-0.55f, -0.86f, 0.55f, -0.80f)
    }
}

object EnergyBar {
    /** Two quads (filled + track), four triangles, twelve vertices for glDrawArrays. */
    const val VERTEX_COUNT: Int = 12
    const val FLOATS_PER_VERTEX: Int = 7

    /**
     * Builds a twelve-vertex pair of quads. The filled portion uses [filledColor] up to [value];
     * the remainder uses [trackColor]. Values are per-vertex so the shader can anti-alias the head.
     */
    fun build(
        value: Float,
        rect: BarRect = BarRect.DEFAULT,
        filledColor: Rgba = Rgba.ENERGY,
        trackColor: Rgba = Rgba.INACTIVE,
    ): FloatArray {
        val data = FloatArray(VERTEX_COUNT * FLOATS_PER_VERTEX)
        write(data, 0, value, rect, filledColor, trackColor)
        return data
    }

    /** Allocation-free variant used by the renderer on every frame. */
    fun write(
        data: FloatArray,
        offsetStart: Int,
        value: Float,
        rect: BarRect = BarRect.DEFAULT,
        filledColor: Rgba = Rgba.ENERGY,
        trackColor: Rgba = Rgba.INACTIVE,
    ) {
        require(data.size >= offsetStart + VERTEX_COUNT * FLOATS_PER_VERTEX) { "buffer too small" }
        val clamped = value.coerceIn(0f, 1f)
        val split = rect.left + (rect.right - rect.left) * clamped
        var offset = offsetStart
        // left/filled quad
        offset = write(data, offset, rect.left, rect.bottom, filledColor, 1f)
        offset = write(data, offset, split, rect.bottom, filledColor, 1f)
        offset = write(data, offset, rect.left, rect.top, filledColor, 1f)
        offset = write(data, offset, split, rect.bottom, filledColor, 1f)
        offset = write(data, offset, split, rect.top, filledColor, 1f)
        offset = write(data, offset, rect.left, rect.top, filledColor, 1f)
        // right/track quad
        offset = write(data, offset, split, rect.bottom, trackColor, 0f)
        offset = write(data, offset, rect.right, rect.bottom, trackColor, 0f)
        offset = write(data, offset, split, rect.top, trackColor, 0f)
        offset = write(data, offset, rect.right, rect.bottom, trackColor, 0f)
        offset = write(data, offset, rect.right, rect.top, trackColor, 0f)
        write(data, offset, split, rect.top, trackColor, 0f)
    }

    private fun write(
        data: FloatArray,
        offset: Int,
        x: Float,
        y: Float,
        color: Rgba,
        value: Float,
    ): Int {
        var i = offset
        data[i++] = x
        data[i++] = y
        data[i++] = color.r
        data[i++] = color.g
        data[i++] = color.b
        data[i++] = color.a
        data[i++] = value
        return i
    }
}
