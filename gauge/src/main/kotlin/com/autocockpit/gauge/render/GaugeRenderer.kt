package com.autocockpit.gauge.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.autocockpit.gauge.math.ArcGaugeSpec
import com.autocockpit.gauge.math.BarRect
import com.autocockpit.gauge.math.EnergyBar
import com.autocockpit.gauge.math.GaugeMesh
import com.autocockpit.gauge.math.Rgba
import com.autocockpit.gauge.math.TickMarks
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0/3.0 gauge renderer.
 *
 * Three layers, one draw call each: the arc strip (GL_TRIANGLE_STRIP), the tick marks
 * (GL_LINES) and the energy bar (GL_TRIANGLES). Geometry is uploaded to VBOs; the arc and bar
 * buffers are rewritten in place every frame, so the render loop allocates nothing.
 *
 * The fragment shader does the smooth value-threshold sweep, the anti-aliased ring edge and the
 * regeneration/consumption colour ramp; `uDegraded` desaturates the whole surface when the
 * property monitor reports LAST_KNOWN or UNAVAILABLE.
 */
class GaugeRenderer(
    private val spec: ArcGaugeSpec = ArcGaugeSpec(),
    private val tickCount: Int = 21,
    private val energyBarRect: BarRect = BarRect.DEFAULT,
    private val smoothing: Float = 0.18f,
) : GLSurfaceView.Renderer {

    @Volatile
    var targetValue: Float = 0f

    @Volatile
    var degraded: Boolean = false

    @Volatile
    var batteryValue: Float = 0.5f

    var useEs3: Boolean = false
        private set
    var programHandle: Int = 0
        private set
    var lastError: String? = null
        private set

    private var smoothedValue: Float = 0f
    private var aspect: Float = 1f
    private val mesh = GaugeMesh.create(spec)
    private val ticks = TickMarks.build(spec, tickCount)
    private val barFloats = FloatArray(EnergyBar.VERTEX_COUNT * EnergyBar.FLOATS_PER_VERTEX)

    private val arcScratch = floatBuffer(mesh.buffer().size)
    private val tickScratch = floatBuffer(ticks.size)
    private val barScratch = floatBuffer(barFloats.size)

    private val vbos = IntArray(3)
    private var positionLocation = 0
    private var colorLocation = 1
    private var valueLocation = 2
    private var uValue = -1
    private var uInner = -1
    private var uOuter = -1
    private var uDegraded = -1
    private var uAspect = -1
    private var uScale = -1

    /** Frames rendered since the surface was created; read by the benchmark. */
    var frameCount: Long = 0
        private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        useEs3 = version.contains("OpenGL ES 3")
        val program = buildProgram(
            vertexSource(useEs3),
            fragmentSource(useEs3),
        )
        if (program == 0) return
        programHandle = program
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        colorLocation = GLES20.glGetAttribLocation(program, "aColor")
        valueLocation = GLES20.glGetAttribLocation(program, "aValue")
        uValue = GLES20.glGetUniformLocation(program, "uValue")
        uInner = GLES20.glGetUniformLocation(program, "uInner")
        uOuter = GLES20.glGetUniformLocation(program, "uOuter")
        uDegraded = GLES20.glGetUniformLocation(program, "uDegraded")
        uAspect = GLES20.glGetUniformLocation(program, "uAspect")
        uScale = GLES20.glGetUniformLocation(program, "uScale")

        GLES20.glGenBuffers(3, vbos, 0)
        allocate(vbos[0], mesh.buffer().size)
        allocate(vbos[1], ticks.size)
        allocate(vbos[2], barFloats.size)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Log.i(TAG, "gauge surface ready (${if (useEs3) "ES 3.0" else "ES 2.0"}): $version")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (programHandle == 0) return
        GLES20.glClearColor(0.016f, 0.024f, 0.032f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frameCount == 0L) smoothedValue = targetValue
        smoothedValue += (targetValue - smoothedValue) * smoothing
        mesh.update(smoothedValue, Rgba.ACTIVE, Rgba.INACTIVE)
        EnergyBar.write(barFloats, 0, batteryValue)

        upload(vbos[0], mesh.buffer(), arcScratch)
        upload(vbos[1], ticks, tickScratch)
        upload(vbos[2], barFloats, barScratch)

        GLES20.glUseProgram(programHandle)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uScale, 0.92f)
        GLES20.glUniform1f(uDegraded, if (degraded) 1f else 0f)
        GLES20.glUniform1f(uValue, smoothedValue)

        drawLayer(vbos[0], mesh.vertexCount(), GLES20.GL_TRIANGLE_STRIP, spec.innerRadius, spec.outerRadius)
        GLES20.glUniform1f(uValue, 1f)
        drawLayer(vbos[1], tickCount * 2, GLES20.GL_LINES, -0.2f, 1.4f)
        drawLayer(vbos[2], EnergyBar.VERTEX_COUNT, GLES20.GL_TRIANGLES, -2f, 2f)

        frameCount++
    }

    fun release() {
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
        if (vbos[0] != 0) {
            GLES20.glDeleteBuffers(3, vbos, 0)
            vbos.fill(0)
        }
    }

    private fun drawLayer(vbo: Int, vertexCount: Int, mode: Int, inner: Float, outer: Float) {
        GLES20.glUniform1f(uInner, inner)
        GLES20.glUniform1f(uOuter, outer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(colorLocation)
        GLES20.glEnableVertexAttribArray(valueLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES20.glVertexAttribPointer(colorLocation, 4, GLES20.GL_FLOAT, false, STRIDE_BYTES, 8)
        GLES20.glVertexAttribPointer(valueLocation, 1, GLES20.GL_FLOAT, false, STRIDE_BYTES, 24)
        GLES20.glDrawArrays(mode, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(colorLocation)
        GLES20.glDisableVertexAttribArray(valueLocation)
    }

    private fun allocate(vbo: Int, floats: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, null, GLES20.GL_DYNAMIC_DRAW)
    }

    private fun upload(vbo: Int, data: FloatArray, scratch: FloatBuffer) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        scratch.clear()
        scratch.put(data)
        scratch.position(0)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, data.size * 4, scratch)
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glBindAttribLocation(program, 0, "aPosition")
        GLES20.glBindAttribLocation(program, 1, "aColor")
        GLES20.glBindAttribLocation(program, 2, "aValue")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] == 0) {
            lastError = "link failed: ${GLES20.glGetProgramInfoLog(program)}"
            Log.e(TAG, lastError!!)
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            lastError = "compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
            Log.e(TAG, lastError!!)
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "GaugeRenderer"
        private const val STRIDE_BYTES = 7 * 4

        internal fun vertexSource(es3: Boolean): String = if (es3) {
            """
            #version 300 es
            uniform float uAspect;
            uniform float uScale;
            in vec2 aPosition;
            in vec4 aColor;
            in float aValue;
            out vec2 vPos;
            out vec4 vColor;
            out float vValue;
            void main() {
                vPos = aPosition;
                vColor = aColor;
                vValue = aValue;
                gl_Position = vec4(aPosition.x / uAspect * uScale, aPosition.y * uScale, 0.0, 1.0);
            }
            """.trimIndent()
        } else {
            """
            uniform float uAspect;
            uniform float uScale;
            attribute vec2 aPosition;
            attribute vec4 aColor;
            attribute float aValue;
            varying vec2 vPos;
            varying vec4 vColor;
            varying float vValue;
            void main() {
                vPos = aPosition;
                vColor = aColor;
                vValue = aValue;
                gl_Position = vec4(aPosition.x / uAspect * uScale, aPosition.y * uScale, 0.0, 1.0);
            }
            """.trimIndent()
        }

        internal fun fragmentSource(es3: Boolean): String {
            val header = if (es3) "#version 300 es\nprecision mediump float;\nout vec4 fragColor;\n" else "precision mediump float;\n"
            val colorOut = if (es3) "fragColor" else "gl_FragColor"
            val varying = if (es3) "in" else "varying"
            return header + """
                uniform float uValue;
                uniform float uInner;
                uniform float uOuter;
                uniform float uDegraded;
                $varying vec2 vPos;
                $varying vec4 vColor;
                $varying float vValue;

                vec3 ramp(float t) {
                    vec3 regen = vec3(0.18, 0.86, 0.62);
                    vec3 mid = vec3(0.98, 0.74, 0.25);
                    vec3 consumption = vec3(1.0, 0.33, 0.24);
                    return t < 0.5 ? mix(regen, mid, t / 0.5) : mix(mid, consumption, (t - 0.5) / 0.5);
                }

                void main() {
                    float r = length(vPos);
                    float aa = 0.008;
                    float ring = smoothstep(uInner - aa, uInner + aa, r) * (1.0 - smoothstep(uOuter - aa, uOuter + aa, r));
                    float filled = smoothstep(uValue + 0.015, uValue - 0.015, vValue);
                    vec3 lit = mix(vColor.rgb, ramp(clamp(vValue, 0.0, 1.0)), 0.55) * (0.55 + 0.45 * filled);
                    float gray = dot(lit, vec3(0.299, 0.587, 0.114));
                    vec3 color = mix(vec3(gray), lit, 1.0 - uDegraded * 0.85);
                    float alpha = ring * vColor.a * mix(0.55, 1.0, filled);
                    $colorOut = vec4(color, alpha);
                }
            """.trimIndent()
        }

        private fun floatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
