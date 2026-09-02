package com.qijing.core.execution

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.qijing.core.model.ExecutionBackend
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

fun interface ShizukuTransport {
    suspend fun execute(command: String): String
}

class ShizukuTransportException(val code: String, message: String) : IllegalStateException(message)

enum class ShizukuState { UNSUPPORTED, SERVICE_UNAVAILABLE, PERMISSION_REQUIRED, READY, ERROR }

data class ShizukuStatus(val state: ShizukuState, val detail: String) {
    val ready: Boolean get() = state == ShizukuState.READY
}

object ShizukuRuntime {
    const val PERMISSION_REQUEST_CODE = 4101

    fun status(): ShizukuStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return ShizukuStatus(ShizukuState.UNSUPPORTED, "Shizuku 后端需要 Android 7 或更高版本")
        }
        return statusApi24()
    }

    fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            if (!Shizuku.pingBinder()) return false
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun statusApi24(): ShizukuStatus = runCatching {
        if (!Shizuku.pingBinder()) {
            ShizukuStatus(ShizukuState.SERVICE_UNAVAILABLE, "Shizuku 服务未运行")
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus(ShizukuState.PERMISSION_REQUIRED, "Shizuku 尚未授权")
        } else {
            ShizukuStatus(ShizukuState.READY, "Shizuku 已连接并授权")
        }
    }.getOrElse { ShizukuStatus(ShizukuState.ERROR, it.message ?: "Shizuku 状态读取失败") }
}

@RequiresApi(Build.VERSION_CODES.N)
class ShizukuUserServiceTransport(
    context: Context,
    private val bindTimeoutMs: Long = 5_000L
) : ShizukuTransport, Closeable {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val args = Shizuku.UserServiceArgs(ComponentName(appContext, QijingUserService::class.java))
        .daemon(false)
        .processNameSuffix("qijing")
        .debuggable((appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        .version(1)
    @Volatile private var service: IQijingUserService? = null
    @Volatile private var pending: CompletableDeferred<IQijingUserService>? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = IQijingUserService.Stub.asInterface(binder)
            service = connected
            pending?.complete(connected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override suspend fun execute(command: String): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Shizuku command cannot be blank" }
        val remote = requireService()
        try {
            remote.execute(command)
        } catch (error: Throwable) {
            service = null
            val message = error.message.orEmpty()
            val code = when {
                "SHIZUKU_TIMEOUT" in message -> "SHIZUKU_TIMEOUT"
                "SHIZUKU_COMMAND_FAILED" in message -> "SHIZUKU_COMMAND_FAILED"
                else -> "SHIZUKU_BINDER_FAILED"
            }
            throw ShizukuTransportException(code, message.ifBlank { "Shizuku user service failed" })
        }
    }

    private suspend fun requireService(): IQijingUserService = mutex.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val status = ShizukuRuntime.status()
        when (status.state) {
            ShizukuState.READY -> Unit
            ShizukuState.PERMISSION_REQUIRED -> throw ShizukuTransportException("SHIZUKU_PERMISSION_REQUIRED", status.detail)
            ShizukuState.SERVICE_UNAVAILABLE -> throw ShizukuTransportException("SHIZUKU_SERVICE_UNAVAILABLE", status.detail)
            ShizukuState.UNSUPPORTED -> throw ShizukuTransportException("SHIZUKU_UNSUPPORTED", status.detail)
            ShizukuState.ERROR -> throw ShizukuTransportException("SHIZUKU_STATUS_ERROR", status.detail)
        }
        val deferred = CompletableDeferred<IQijingUserService>().also { pending = it }
        try {
            Shizuku.bindUserService(args, connection)
            withTimeout(bindTimeoutMs) { deferred.await() }
        } catch (error: Throwable) {
            throw ShizukuTransportException("SHIZUKU_BIND_FAILED", error.message ?: "Unable to bind Shizuku user service")
        } finally {
            pending = null
        }
    }

    override fun close() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        pending?.cancel()
        pending = null
    }
}

class ShizukuExecutionBroker(private val transport: ShizukuTransport) : ExecutionBroker, CommandValidator, RequiresRollbackSnapshot, ExecutionBackendProvider {
    override val executionBackend: ExecutionBackend = ExecutionBackend.SHIZUKU
    override fun validate(command: CapabilityCommand): ExecutionResult? {
        val base = command.capability.removeSuffix(".restore")
        if (base == "scheduler.profile.gesture_boost.configure" || base == "scheduler.profile.limiter.clear") {
            return ExecutionResult.Unsupported(command.capability, "该常驻调度能力仅支持明确选择的 Root 后端")
        }
        if (base == "scheduler.profile.limiter.cluster.set") {
            val cluster = ProfileLimiterCommandPolicy.parse(command)
            if (cluster != null && ManagedLimiterRuntime.isManaged(cluster)) {
                return ExecutionResult.Unsupported(command.capability, "动态 limiter worker 仅支持明确选择的 Root 后端")
            }
        }
        return PrivilegedWriteCommandMapper.validationResult(command, "SHIZUKU")
    }

    override suspend fun execute(command: CapabilityCommand): ExecutionResult {
        validate(command)?.let { return it }
        val shell = (PrivilegedWriteCommandMapper.map(command) as PrivilegedWriteCommandMapper.Result.Command).shell
        return try {
            ExecutionResult.Applied(ExecutionBackend.SHIZUKU, transport.execute(shell))
        } catch (error: ShizukuTransportException) {
            ExecutionResult.Failed(error.code, error.message ?: "Shizuku execution failed", command.rollback)
        } catch (error: Throwable) {
            ExecutionResult.Failed("SHIZUKU_EXECUTION_FAILED", error.message ?: "Shizuku execution failed", command.rollback)
        }
    }
}
