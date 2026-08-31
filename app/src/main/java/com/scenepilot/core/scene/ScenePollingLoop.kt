package com.scenepilot.core.scene

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-friendly event loop. It emits only when the foreground package changes;
 * the host decides whether to call SceneActivationCoordinator.
 */
class ScenePollingLoop(
    private val source: ForegroundAppSource,
    private val intervalMs: Long = 2_000L,
    private val onPackageChanged: (String) -> Unit
) {
    private var executor: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)
    @Volatile private var lastPackage: String? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "scenepilot-trigger").apply { isDaemon = true } }.also { pool ->
            pool.scheduleWithFixedDelay(::pollOnce, 0, intervalMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor?.shutdownNow(); executor = null; lastPackage = null
    }

    fun pollNow() { pollPackage() }

    private fun pollOnce() {
        if (!running.get()) return
        pollPackage()
    }

    private fun pollPackage() {
        val current = runCatching { source.currentPackageName() }.getOrNull() ?: return
        if (current != lastPackage) { lastPackage = current; onPackageChanged(current) }
    }
}
