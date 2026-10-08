// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.baseline

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** An EGL consumer owned by the test. Only the upstream MediaCodec writes this Surface. */
@TargetApi(27)
internal class SurfaceFrameProbe : Closeable {
    val frames = AtomicInteger()
    val redPixels = AtomicInteger()
    val timestamp = AtomicLong()
    private val error = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("ts7-test-surface").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var output: Surface? = null
    private var textureId = 0
    private var program = 0
    val surface: Surface get() = output ?: throw BaselineFailure("TEST_SURFACE_UNAVAILABLE")

    init {
        try {
            call {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                demand(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0), "EGL_INITIALIZE_FAILED")
                val configs = arrayOfNulls<EGLConfig>(1)
                val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
                val count = IntArray(1)
                demand(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0, "EGL_CONFIG_FAILED")
                eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                demand(eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE &&
                    EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext), "EGL_CURRENT_FAILED")
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                program = program()
                texture = SurfaceTexture(textureId).also { consumer ->
                    consumer.setDefaultBufferSize(160, 96)
                    consumer.setOnFrameAvailableListener({ frame ->
                        if (!closed.get()) {
                            try {
                                frame.updateTexImage()
                                timestamp.set(frame.timestamp)
                                val transform = FloatArray(16)
                                frame.getTransformMatrix(transform)
                                if (readRedPixel(transform)) redPixels.incrementAndGet()
                                frames.incrementAndGet()
                            } catch (_: Throwable) { error.set(true) }
                        }
                    }, handler)
                    output = Surface(consumer)
                }
                demand(surface.isValid, "TEST_SURFACE_INVALID")
            }
        } catch (failure: Throwable) {
            runCatching { close() }
            throw failure
        }
    }

    fun demandHealthy() { demand(!error.get(), "SURFACE_CALLBACK_FAILED") }
    fun releasedSurfaceIsValid() = output?.isValid == true

    private fun readRedPixel(transform: FloatArray): Boolean {
        val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        GLES20.glViewport(0, 0, 16, 16)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val pixel = ByteBuffer.allocateDirect(4)
        GLES20.glReadPixels(8, 8, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
        GLES20.glFinish()
        demand(GLES20.glGetError() == GLES20.GL_NO_ERROR, "FRAME_PIXEL_READ_FAILED")
        return (pixel.get(0).toInt() and 255) > 180 && (pixel.get(1).toInt() and 255) < 80 &&
            (pixel.get(2).toInt() and 255) < 80
    }

    private fun program(): Int {
        fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val result = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0)
            demand(result[0] == 1, "TEST_SHADER_COMPILE_FAILED")
            return shader
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec2 uv; uniform mat4 transform; varying vec2 coord; void main(){gl_Position=position; coord=(transform*vec4(uv,0.0,1.0)).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 coord; void main(){gl_FragColor=texture2D(image,coord);}")
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val result = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
        demand(result[0] == 1, "TEST_SHADER_LINK_FAILED")
        return program
    }

    private fun call(action: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        demand(handler.post {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }, "EGL_THREAD_NOT_AVAILABLE")
        demand(done.await(10, TimeUnit.SECONDS), "EGL_THREAD_TIMEOUT")
        failure.get()?.let { throw it }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            call {
                texture?.setOnFrameAvailableListener(null)
                output?.release(); texture?.release()
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display); EGL14.eglReleaseThread()
                }
            }
        } finally {
            thread.quitSafely(); thread.join(5_000)
            demand(!thread.isAlive, "EGL_THREAD_CLEANUP_TIMEOUT")
        }
    }
}
