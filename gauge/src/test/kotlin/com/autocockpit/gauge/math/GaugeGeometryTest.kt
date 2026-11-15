package com.autocockpit.gauge.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class GaugeGeometryTest {

    private val spec = ArcGaugeSpec(segments = 64)

    @Test
    fun vertexCountFollowsSegmentCount() {
        assertEquals((64 + 1) * 2, spec.vertexCount)
        val mesh = GaugeMesh(spec)
        assertEquals(spec.vertexCount, mesh.vertexCount())
        assertEquals(spec.vertexCount * ArcGaugeSpec.FLOATS_PER_VERTEX, mesh.buffer().size)
    }

    @Test
    fun bufferIsInterleavedPositionColorValue() {
        val mesh = GaugeMesh(spec)
        mesh.update(0.5f)
        val floats = mesh.buffer()
        val stride = ArcGaugeSpec.FLOATS_PER_VERTEX
        // Position of the first vertex lies on the inner radius.
        val x = floats[0]
        val y = floats[1]
        assertEquals(spec.innerRadius, hypot(x, y), 0.001f)
        // Alpha is opaque, value starts at zero and reaches one at the end of the strip.
        assertEquals(1f, floats[5], 0.001f)
        assertEquals(0f, floats[6], 0.001f)
        assertEquals(1f, floats[floats.size - 1], 0.001f)
    }

    @Test
    fun allVerticesStayWithinTheAnnulus() {
        val mesh = GaugeMesh(spec)
        mesh.update(0.7f)
        val floats = mesh.buffer()
        var offset = 0
        while (offset < floats.size) {
            val radius = hypot(floats[offset], floats[offset + 1])
            assertTrue("radius $radius outside annulus", radius >= spec.innerRadius - 1e-4f)
            assertTrue("radius $radius outside annulus", radius <= spec.outerRadius + 1e-4f)
            offset += ArcGaugeSpec.FLOATS_PER_VERTEX
        }
    }

    @Test
    fun anglesIncreaseMonotonicallyAlongTheStrip() {
        val mesh = GaugeMesh(spec)
        mesh.update(1f)
        val floats = mesh.buffer()
        var previous = Float.NEGATIVE_INFINITY
        var unwrapped = 0f
        var previousRaw = 0f
        for (i in 0..spec.segments) {
            val idx = i * 2 * ArcGaugeSpec.FLOATS_PER_VERTEX
            val angle = Math.toDegrees(atan2(floats[idx + 1].toDouble(), floats[idx].toDouble())).toFloat()
            if (i == 0) {
                previousRaw = angle
                unwrapped = angle
                previous = unwrapped
                continue
            }
            var delta = angle - previousRaw
            if (delta < -180f) delta += 360f
            if (delta > 180f) delta -= 360f
            unwrapped += delta
            previousRaw = angle
            assertTrue("angle regressed at segment $i", unwrapped > previous)
            previous = unwrapped
        }
        assertEquals(spec.startAngleDegrees + spec.sweepDegrees, unwrapped, 1.5f)
    }

    @Test
    fun valueMapsToAngleAtZeroFiftyAndHundred() {
        assertEquals(135f, spec.angleForValue(0f), 0.001f)
        assertEquals(270f, spec.angleForValue(0.5f), 0.001f)
        assertEquals(405f, spec.angleForValue(1f), 0.001f)
    }

    @Test
    fun valueIsClampedToTheSweep() {
        assertEquals(135f, spec.angleForValue(-3f), 0.001f)
        assertEquals(405f, spec.angleForValue(9f), 0.001f)
    }

    @Test
    fun pointOnArcUsesScreenSpaceDegrees() {
        val east = spec.pointAt(1f, 0f)
        assertEquals(1f, east.x, 1e-4f)
        assertEquals(0f, east.y, 1e-4f)
        val south = spec.pointAt(1f, 90f)
        assertEquals(0f, south.x, 1e-4f)
        assertEquals(1f, south.y, 1e-4f)
    }

    @Test
    fun updateIsAllocationFreeAndReusesTheSameBuffer() {
        val mesh = GaugeMesh.create(spec)
        val first = mesh.update(0f)
        val second = mesh.update(0.5f)
        val third = mesh.update(1f)
        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, mesh.allocationCount)
        assertEquals(3, mesh.updateCount)
    }

    @Test
    fun updateDoesNotChangeBufferCapacityAfterManyFrames() {
        val mesh = GaugeMesh.create(spec)
        val size = mesh.buffer().size
        repeat(10_000) { mesh.update((it % 100) / 100f) }
        assertEquals(size, mesh.buffer().size)
        assertEquals(1, mesh.allocationCount)
    }

    @Test
    fun filledPortionUsesTheActiveColour() {
        val mesh = GaugeMesh(spec)
        mesh.update(0.5f, activeColor = Rgba.ACTIVE, inactiveColor = Rgba.INACTIVE)
        val floats = mesh.buffer()
        val stride = ArcGaugeSpec.FLOATS_PER_VERTEX
        val firstColor = floats[2]
        val lastColor = floats[floats.size - stride + 2]
        assertEquals(Rgba.ACTIVE.r, firstColor, 0.001f)
        assertEquals(Rgba.INACTIVE.r, lastColor, 0.001f)
    }

    @Test
    fun emptyValueLeavesTheWholeArcInactive() {
        val mesh = GaugeMesh(spec)
        mesh.update(0f)
        val floats = mesh.buffer()
        assertEquals(Rgba.INACTIVE.r, floats[2], 0.001f)
        assertEquals(Rgba.INACTIVE.g, floats[3], 0.001f)
    }

    @Test
    fun specRejectsInvalidGeometry() {
        assertTrue(runCatching { ArcGaugeSpec(segments = 2) }.isFailure)
        assertTrue(runCatching { ArcGaugeSpec(innerRadius = 0.9f, outerRadius = 0.5f) }.isFailure)
        assertTrue(runCatching { ArcGaugeSpec(outerRadius = 1.2f) }.isFailure)
        assertTrue(runCatching { ArcGaugeSpec(sweepDegrees = 0f) }.isFailure)
    }

    @Test
    fun ticksHaveTwoVerticesEachAndAlternateMajorLength() {
        val ticks = TickMarks.build(spec, tickCount = 21, majorEvery = 5)
        assertEquals(TickMarks.vertexCount(21), 42)
        assertEquals(42 * TickMarks.FLOATS_PER_VERTEX, ticks.size)
        fun radiusAt(vertex: Int): Float {
            val offset = vertex * TickMarks.FLOATS_PER_VERTEX
            return hypot(ticks[offset], ticks[offset + 1])
        }
        // Vertex 0 and 1 form the first tick (major), 2 and 3 the second (minor).
        val majorLength = radiusAt(1) - radiusAt(0)
        val minorLength = radiusAt(3) - radiusAt(2)
        assertTrue("major $majorLength should exceed minor $minorLength", majorLength > minorLength)
        assertTrue(radiusAt(0) > spec.outerRadius)
    }

    @Test
    fun tickAnglesSpanTheSweep() {
        val ticks = TickMarks.build(spec, tickCount = 5)
        var previousRaw = 0f
        var unwrapped = 0f
        var previous = Float.NEGATIVE_INFINITY
        for (i in 0 until 5) {
            val offset = (i * 2) * TickMarks.FLOATS_PER_VERTEX
            val angle = Math.toDegrees(atan2(ticks[offset + 1].toDouble(), ticks[offset].toDouble())).toFloat()
            if (i == 0) {
                previousRaw = angle
                unwrapped = angle
                previous = angle
                continue
            }
            var delta = angle - previousRaw
            if (delta < -180f) delta += 360f
            if (delta > 180f) delta -= 360f
            unwrapped += delta
            previousRaw = angle
            assertTrue("tick $i moved backwards", unwrapped > previous)
            previous = unwrapped
        }
        assertEquals(spec.startAngleDegrees + spec.sweepDegrees, unwrapped, 1.5f)
    }

    @Test
    fun energyBarHasTwoQuads() {
        val bar = EnergyBar.build(0.5f)
        assertEquals(EnergyBar.VERTEX_COUNT * EnergyBar.FLOATS_PER_VERTEX, bar.size)
        assertEquals(12, EnergyBar.VERTEX_COUNT)
    }

    @Test
    fun energyBarSplitTracksValue() {
        val rect = BarRect(-1f, -1f, 1f, 0f)
        val half = EnergyBar.build(0.5f, rect)
        assertEquals(-1f, half[0], 0.001f) // left edge starts at rect.left
        // The second vertex sits at the split point (x = 0).
        val splitVertex = 1 * EnergyBar.FLOATS_PER_VERTEX
        assertEquals(0f, half[splitVertex], 0.001f)
        val empty = EnergyBar.build(0f, rect)
        assertEquals(-1f, empty[splitVertex], 0.001f)
        val full = EnergyBar.build(1f, rect)
        assertEquals(1f, full[splitVertex], 0.001f)
    }

    @Test
    fun energyBarWriteMatchesBuild() {
        val rect = BarRect.DEFAULT
        val built = EnergyBar.build(0.42f, rect)
        val target = FloatArray(built.size)
        EnergyBar.write(target, 0, 0.42f, rect)
        assertTrue(built.contentEquals(target))
    }

    @Test
    fun barRectRejectsInvertedBounds() {
        assertTrue(runCatching { BarRect(1f, 0f, 0f, 1f) }.isFailure)
        assertTrue(runCatching { BarRect(0f, 1f, 1f, 0f) }.isFailure)
    }
}
