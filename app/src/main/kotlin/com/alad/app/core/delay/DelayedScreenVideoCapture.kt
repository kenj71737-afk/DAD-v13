package com.alad.app.core.delay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File

/**
 * Captures the content selected in Android's MediaProjection picker.
 * On Android 14+ the user can select Chrome's app window; the virtual display then
 * receives that window rather than the complete ALAD/Chrome display composition.
 * Video-only segments are intentional: the live AudioPlaybackCapture/Gemini branch
 * remains separate and is not blocked by the video recorder.
 */
class DelayedScreenVideoCapture(private val context: Context) {
    companion object {
        private const val TAG = "ALADVideoCapture"
        private const val SEGMENT_MS = 2000L
        private const val FALLBACK_BITRATE = 3_000_000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var running = false
    private var segmentStart = 0L
    private var outputDir: File? = null
    private var captureWidth = 720
    private var captureHeight = 1280
    private var pendingFile: File? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= 34 && width > 0 && height > 0) {
                handler.post { resizeCapture(width, height) }
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.i(TAG, "Selected app-window visibility=$isVisible")
        }
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            rotateSegment()
            if (running) handler.postDelayed(this, SEGMENT_MS)
        }
    }

    fun start(mediaProjection: MediaProjection): Boolean {
        if (running) return true
        projection = mediaProjection
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        // Portrait-safe fallback only. Android 14's onCapturedContentResize replaces
        // these dimensions with the actual selected Chrome window dimensions.
        val portrait = metrics.heightPixels >= metrics.widthPixels
        captureWidth = if (portrait) 720 else 1280
        captureHeight = if (portrait) 1280 else 720
        outputDir = File(context.cacheDir, "delayed_video").apply { mkdirs() }
        return try {
            mediaProjection.registerCallback(projectionCallback, handler)
            val first = createRecorder(captureWidth, captureHeight)
            display = mediaProjection.createVirtualDisplay(
                "ALAD-Chrome-AppWindow", captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                first.surface, null, handler
            )
            recorder = first
            segmentStart = SystemClock.elapsedRealtime()
            running = true
            handler.postDelayed(rotate, SEGMENT_MS)
            Log.i(TAG, "Capture started; waiting for selected-content dimensions")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start selected-window video capture", t)
            runCatching { recorder?.release() }; recorder = null
            runCatching { display?.release() }; display = null
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            false
        }
    }

    private fun even(value: Int): Int = value.coerceAtLeast(2).let { it - (it % 2) }

    private fun createRecorder(width: Int, height: Int): MediaRecorder {
        val out = File(outputDir, "seg_${SystemClock.elapsedRealtime()}.mp4")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(out.absolutePath)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(even(width), even(height))
        r.setVideoFrameRate(24)
        r.setVideoEncodingBitRate(FALLBACK_BITRATE)
        r.setOnErrorListener { _, what, extra -> Log.e(TAG, "MediaRecorder error $what/$extra") }
        r.prepare()
        r.start()
        pendingFile = out
        return r
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (!running) return
        val newW = even(width)
        val newH = even(height)
        if (newW == captureWidth && newH == captureHeight) return
        Log.i(TAG, "Selected app window resized: ${newW}x${newH}")
        captureWidth = newW
        captureHeight = newH
        rotateSegment()
    }

    private fun finishCurrentSegment(end: Long) {
        val old = recorder ?: return
        val file = pendingFile
        runCatching { old.stop() }.onFailure {
            Log.w(TAG, "Discarding incomplete video segment", it)
            file?.delete()
        }
        runCatching { old.reset(); old.release() }
        recorder = null
        pendingFile = null
        if (file != null && file.exists() && file.length() > 4096L) {
            DelayedVideoSegmentStore.add(DelayedVideoSegmentStore.Segment(file, segmentStart, end))
        }
    }

    private fun rotateSegment() {
        if (!running) return
        val end = SystemClock.elapsedRealtime()
        finishCurrentSegment(end)
        try {
            val next = createRecorder(captureWidth, captureHeight)
            display?.setSurface(next.surface)
            recorder = next
            segmentStart = end
        } catch (t: Throwable) {
            Log.e(TAG, "Video segment rotation failed", t)
            running = false
            runCatching { display?.setSurface(null) }
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(rotate)
        val end = SystemClock.elapsedRealtime()
        finishCurrentSegment(end)
        runCatching { display?.setSurface(null); display?.release() }
        display = null
        projection?.let { runCatching { it.unregisterCallback(projectionCallback) } }
        projection = null
    }
}
