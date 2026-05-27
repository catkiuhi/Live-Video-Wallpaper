package com.example

import android.content.Context
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.*
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return VideoEngine()
    }

    inner class VideoEngine : Engine() {
        private var eglHelper: EglHelper? = null
        private var renderer: VideoGlRenderer? = null
        private var mediaPlayer: MediaPlayer? = null
        private var surfaceTexture: SurfaceTexture? = null
        private var renderThread: Thread? = null
        private var isRunning = false
        private var isWallpaperVisible = false
        private val lock = Object()
        private var frameAvailable = false
        private var triggerVideoReload = false

        // Settings (cached in memory)
        private var scale = 1.0f
        private var offsetX = 0.0f
        private var offsetY = 0.0f
        private var rotationAngle = 0.0f
        private var isMuted = true
        private var resetOnLock = false

        private var width = 0
        private var height = 0

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            synchronized(lock) {
                when (key) {
                    "video_scale" -> scale = prefs.getFloat("video_scale", 1.0f)
                    "video_offset_x" -> offsetX = prefs.getFloat("video_offset_x", 0.0f)
                    "video_offset_y" -> offsetY = prefs.getFloat("video_offset_y", 0.0f)
                    "video_rotation" -> rotationAngle = prefs.getFloat("video_rotation", 0.0f)
                    "video_reset_on_lock" -> resetOnLock = prefs.getBoolean("video_reset_on_lock", false)
                    "video_muted" -> {
                        isMuted = prefs.getBoolean("video_muted", true)
                        mediaPlayer?.apply {
                            val vol = if (isMuted) 0f else 1f
                            setVolume(vol, vol)
                        }
                    }
                    "video_uri" -> {
                        triggerVideoReload = true
                    }
                }
                // Force wake up render loop for instant slider feedback during live adjustments!
                frameAvailable = true
                lock.notifyAll()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            val prefs = getSharedPreferences("VideoWallpaperPrefs", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
            
            // Load initial values
            scale = prefs.getFloat("video_scale", 1.0f)
            offsetX = prefs.getFloat("video_offset_x", 0.0f)
            offsetY = prefs.getFloat("video_offset_y", 0.0f)
            rotationAngle = prefs.getFloat("video_rotation", 0.0f)
            isMuted = prefs.getBoolean("video_muted", true)
            resetOnLock = prefs.getBoolean("video_reset_on_lock", false)
            isWallpaperVisible = isVisible
        }

        override fun onDestroy() {
            super.onDestroy()
            val prefs = getSharedPreferences("VideoWallpaperPrefs", Context.MODE_PRIVATE)
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            stopRenderThread()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startRenderThread(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w
            height = h
            synchronized(lock) {
                lock.notifyAll()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopRenderThread()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            synchronized(lock) {
                isWallpaperVisible = visible
                if (visible) {
                    try {
                        if (mediaPlayer?.isPlaying == false) {
                            mediaPlayer?.start()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    frameAvailable = true
                } else {
                    try {
                        if (mediaPlayer?.isPlaying == true) {
                            mediaPlayer?.pause()
                        }
                        if (resetOnLock) {
                            mediaPlayer?.seekTo(0)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                lock.notifyAll()
            }
        }

        private fun startRenderThread(holder: SurfaceHolder) {
            isRunning = true
            renderThread = Thread {
                val egl = EglHelper()
                if (!egl.init(holder.surface)) {
                    return@Thread
                }
                eglHelper = egl

                val render = VideoGlRenderer()
                render.init()
                renderer = render

                val textId = render.getTextureId()
                val tex = SurfaceTexture(textId)
                surfaceTexture = tex

                tex.setOnFrameAvailableListener {
                    synchronized(lock) {
                        frameAvailable = true
                        lock.notifyAll()
                    }
                }

                val videoSurface = android.view.Surface(tex)
                setupMediaPlayer(videoSurface)

                val sTMatrix = FloatArray(16)

                while (isRunning) {
                    var reloadVideo = false
                    synchronized(lock) {
                        while (isRunning && (!isWallpaperVisible || (!frameAvailable && !triggerVideoReload))) {
                            try {
                                lock.wait()
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                        if (!isRunning) break
                        if (triggerVideoReload) {
                            triggerVideoReload = false
                            reloadVideo = true
                        }
                        frameAvailable = false
                    }

                    // Deep freeze check: absolutely do not draw anything or swap buffers if not visible
                    if (!isWallpaperVisible) {
                        continue
                    }

                    if (reloadVideo) {
                        try {
                            setupMediaPlayer(videoSurface)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    try {
                        egl.makeCurrent()
                        tex.updateTexImage()
                        tex.getTransformMatrix(sTMatrix)

                        GLES20.glViewport(0, 0, width, height)
                        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                        render.draw(sTMatrix, scale, offsetX, offsetY, rotationAngle)
                        egl.swapBuffers()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                releaseMediaPlayer()
                videoSurface.release()
                tex.release()
                render.release()
                egl.release()
            }.apply { start() }
        }

        private fun stopRenderThread() {
            isRunning = false
            synchronized(lock) {
                lock.notifyAll()
            }
            try {
                renderThread?.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            renderThread = null
            eglHelper = null
            renderer = null
            surfaceTexture = null
        }

        private fun releaseMediaPlayer() {
            try {
                mediaPlayer?.stop()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                mediaPlayer?.release()
            } catch (e: Exception) {
                // Ignore
            }
            mediaPlayer = null
        }

        private fun setupMediaPlayer(surface: android.view.Surface) {
            val prefs = getSharedPreferences("VideoWallpaperPrefs", Context.MODE_PRIVATE)
            val uriString = prefs.getString("video_uri", null)

            if (uriString != null) {
                try {
                    releaseMediaPlayer()
                    mediaPlayer = MediaPlayer().apply {
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.e("VideoWallpaperService", "MediaPlayer error: what=$what, extra=$extra")
                            true // Handle error gracefully, preventing crashes
                        }
                        setSurface(surface)
                        setDataSource(applicationContext, Uri.parse(uriString))
                        isLooping = true
                        val vol = if (isMuted) 0f else 1f
                        setVolume(vol, vol)
                        setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        prepare()
                        if (isWallpaperVisible) {
                            start()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

class EglHelper {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun init(surface: Any): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) return false

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        return true
    }

    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

class VideoGlRenderer {
    private val vertexShaderCode = """
        attribute vec4 position;
        attribute vec4 inputTextureCoordinate;
        varying vec2 textureCoordinate;
        uniform mat4 uSTMatrix;
        void main() {
            gl_Position = position;
            textureCoordinate = (uSTMatrix * inputTextureCoordinate).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform samplerExternalOES videoTex;
        uniform float uScale;
        uniform float uOffsetX;
        uniform float uOffsetY;
        uniform float uRotation;
        void main() {
            vec2 uv = textureCoordinate;
            
            // Apply rotation around the center (0.5, 0.5)
            vec2 d = uv - 0.5;
            float rad = radians(uRotation);
            float cosAngle = cos(rad);
            float sinAngle = sin(rad);
            vec2 rotated;
            rotated.x = d.x * cosAngle - d.y * sinAngle;
            rotated.y = d.x * sinAngle + d.y * cosAngle;
            uv = rotated + 0.5;

            uv = (uv - 0.5) / uScale + 0.5;
            uv.x -= uOffsetX;
            uv.y -= uOffsetY;
            if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
                gl_FragColor = texture2D(videoTex, uv);
            } else {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            }
        }
    """.trimIndent()

    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var stMatrixHandle = 0
    private var scaleHandle = 0
    private var offsetXHandle = 0
    private var offsetYHandle = 0
    private var rotationHandle = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private val vertices = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )

    private val texCoordinates = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    fun init() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        }

        texBuffer = ByteBuffer.allocateDirect(texCoordinates.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(texCoordinates)
                position(0)
            }
        }
    }

    fun draw(stMatrix: FloatArray, scale: Float, offsetX: Float, offsetY: Float, rotation: Float) {
        GLES20.glUseProgram(program)

        positionHandle = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        texCoordHandle = GLES20.glGetAttribLocation(program, "inputTextureCoordinate")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)

        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        GLES20.glUniform1f(scaleHandle, scale)

        offsetXHandle = GLES20.glGetUniformLocation(program, "uOffsetX")
        GLES20.glUniform1f(offsetXHandle, offsetX)

        offsetYHandle = GLES20.glGetUniformLocation(program, "uOffsetY")
        GLES20.glUniform1f(offsetYHandle, offsetY)

        rotationHandle = GLES20.glGetUniformLocation(program, "uRotation")
        GLES20.glUniform1f(rotationHandle, rotation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun getTextureId(): Int = textureId

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
