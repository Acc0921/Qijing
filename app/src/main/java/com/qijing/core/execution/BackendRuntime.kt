package com.qijing.core.execution

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.qijing.core.model.ExecutionBackend
import java.io.Closeable
import java.io.File

class BackendPreference(context: Context) {
    private val prefs = context.getSharedPreferences("qijing_backend_v1", Context.MODE_PRIVATE)

    fun selected(): ExecutionBackend = prefs.getString(KEY, null)
        ?.let { runCatching { ExecutionBackend.valueOf(it) }.getOrNull() }
        ?.takeIf { it in SELECTABLE }
        ?: ExecutionBackend.DRY_RUN

    fun select(backend: ExecutionBackend) {
        require(backend in SELECTABLE) { "Backend is not selectable" }
        prefs.edit().putString(KEY, backend.name).apply()
    }

    private companion object {
        const val KEY = "selected"
        val SELECTABLE = setOf(ExecutionBackend.DRY_RUN, ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU)
    }
}

data class BackendRuntime(
    val backend: ExecutionBackend,
    val broker: ExecutionBroker,
    private val resource: Closeable? = null
) : Closeable {
    override fun close() { resource?.close() }
}

object BackendRuntimeFactory {
    fun create(context: Context, backend: ExecutionBackend): BackendRuntime = when (backend) {
        ExecutionBackend.ROOT -> {
            val su = listOf(File("/system/bin/su"), File("/system/xbin/su")).firstOrNull(File::canExecute)
            if (su == null) unavailable(backend, "未发现可执行 su")
            else BackendRuntime(backend, RootExecutionBroker(ProcessSuTransport(su)))
        }
        ExecutionBackend.SHIZUKU -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) unavailable(backend, "Shizuku 需要 Android 7 或更高版本")
            else createShizuku(context)
        }
        ExecutionBackend.DRY_RUN -> BackendRuntime(backend, DryRunExecutionBroker())
        else -> unavailable(backend, "第一版尚未装配该后端")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun createShizuku(context: Context): BackendRuntime {
        val status = ShizukuRuntime.status()
        if (!status.ready) return unavailable(ExecutionBackend.SHIZUKU, status.detail)
        val transport = ShizukuUserServiceTransport(context)
        return BackendRuntime(ExecutionBackend.SHIZUKU, ShizukuExecutionBroker(transport), transport)
    }

    private fun unavailable(backend: ExecutionBackend, reason: String): BackendRuntime =
        BackendRuntime(backend, UnavailableExecutionBroker(backend, reason))
}

class UnavailableExecutionBroker(
    private val backend: ExecutionBackend,
    private val reason: String
) : ExecutionBroker, CommandValidator {
    override fun validate(command: CapabilityCommand): ExecutionResult =
        ExecutionResult.Unsupported(command.capability, "${backend.name}: $reason")

    override suspend fun execute(command: CapabilityCommand): ExecutionResult = validate(command)
}
