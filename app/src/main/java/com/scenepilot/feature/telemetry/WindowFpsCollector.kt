package com.scenepilot.feature.telemetry

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.RequiresApi

/** Collects this app window's frame timings. External app/game capture needs a separate backend. */
@RequiresApi(Build.VERSION_CODES.N)
class WindowFpsCollector(
    private val activity: Activity,
    private val monitor: FpsMonitor,
    private val windowDurationMs: Long = 1_000L,
    private val onSample: (FpsWindowSample) -> Unit = {}
) {
    @Suppress("DEPRECATION")
    private val refreshRateHz: Float
        get() = activity.windowManager.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var mainHandler: Handler? = null
    private var accumulator: FpsWindowAccumulator? = null
    private var sessionId: String? = null
    @Volatile private var running = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (!running) return@OnFrameMetricsAvailableListener
        val current = accumulator ?: return@OnFrameMetricsAvailableListener
        current.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION), dropped)
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return false
        val worker = HandlerThread("frame-domain-fps").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
        mainHandler = Handler(activity.mainLooper)
        accumulator = FpsWindowAccumulator(refreshRateHz)
        sessionId = monitor.start()
        running = true
        activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        handler?.postDelayed(::flushWindow, windowDurationMs)
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (!running) return false
        running = false
        activity.window.removeOnFrameMetricsAvailableListener(listener)
        flushWindow()
        monitor.stop()
        handler = null
        mainHandler = null
        accumulator = null
        thread?.quitSafely()
        thread = null
        sessionId = null
        return true
    }

    private fun flushWindow() {
        val sample = accumulator?.flush()
        if (sample != null) {
            monitor.record(sample.fps, sample.averageFrameTimeMs, sample.jankCount)
            mainHandler?.post { onSample(sample) }
        }
        if (running) handler?.postDelayed(::flushWindow, windowDurationMs)
    }
}
