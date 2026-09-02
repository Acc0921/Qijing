package com.qijing.core.execution

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.qijing.core.data.SharedPreferencesNewDataStore
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.scene.SceneServiceStatePolicy
import com.qijing.core.scene.SceneServiceStateStore
import java.io.Closeable
import java.io.File

class BackendPreference(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("qijing_backend_v1", Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean(SCENE_APPROVAL_CONTRACT, false)) {
            disableEnabledScenes()
            prefs.edit().putBoolean(SCENE_APPROVAL_CONTRACT, true).apply()
        }
    }

    fun selected(): ExecutionBackend = prefs.getString(KEY, null)
        ?.let { runCatching { ExecutionBackend.valueOf(it) }.getOrNull() }
        ?.takeIf { it in SELECTABLE }
        ?: ExecutionBackend.DRY_RUN

    fun select(backend: ExecutionBackend): BackendSelectionResult {
        require(backend in SELECTABLE) { "Backend is not selectable" }
        if (selected() == backend) return BackendSelectionResult.UNCHANGED
        if (!SceneServiceStatePolicy.canSwitchBackend(SceneServiceStateStore(appContext).current())) {
            return BackendSelectionResult.BLOCKED_SERVICE_ACTIVE
        }
        disableEnabledScenes()
        prefs.edit().putString(KEY, backend.name).commit()
        return BackendSelectionResult.SELECTED
    }

    private fun disableEnabledScenes() {
        val store = SharedPreferencesNewDataStore(appContext)
        store.scenes().filter { it.enabled }.forEach { store.saveScene(it.copy(enabled = false)) }
    }

    private companion object {
        const val KEY = "selected"
        const val SCENE_APPROVAL_CONTRACT = "scene_approval_contract_v1"
        val SELECTABLE = setOf(ExecutionBackend.DRY_RUN, ExecutionBackend.ROOT, ExecutionBackend.SHIZUKU)
    }
}

enum class BackendSelectionResult { SELECTED, UNCHANGED, BLOCKED_SERVICE_ACTIVE }

data class BackendRuntime(
    val backend: ExecutionBackend,
    val broker: ExecutionBroker,
    val readCapability: (suspend (String) -> String?)? = null,
    private val resource: Closeable? = null
) : Closeable {
    override fun close() { resource?.close() }
}

object BackendRuntimeFactory {
    fun create(context: Context, backend: ExecutionBackend): BackendRuntime = when (backend) {
        ExecutionBackend.ROOT -> {
            val su = listOf(File("/system/bin/su"), File("/system/xbin/su")).firstOrNull(File::canExecute)
            if (su == null) unavailable(backend, "未发现可执行 su")
            else {
                val transport = ProcessSuTransport(su)
                BackendRuntime(backend, RootExecutionBroker(transport), readCapability = privilegedReader(transport::execute))
            }
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
        return BackendRuntime(ExecutionBackend.SHIZUKU, ShizukuExecutionBroker(transport), privilegedReader(transport::execute), transport)
    }

    private fun unavailable(backend: ExecutionBackend, reason: String): BackendRuntime =
        BackendRuntime(backend, UnavailableExecutionBroker(backend, reason))

    private fun privilegedReader(execute: suspend (String) -> String): suspend (String) -> String? = { capability ->
        val command = PrivilegedReadCommandMapper.map(capability)
        if (command == null) null else runCatching { execute(command).trim().ifBlank { null } }.getOrNull()
    }
}

/** Fixed read-only templates used to capture rollback values before any privileged write. */
object PrivilegedReadCommandMapper {
    fun map(capability: String): String? {
        POLICY_CAPABILITY.matchEntire(capability)?.let { match ->
            val policyId = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val node = when (match.groupValues[2]) {
                "governor" -> "scaling_governor"
                "min_frequency" -> "scaling_min_freq"
                "max_frequency" -> "scaling_max_freq"
                else -> return null
            }
            return "tr -d '[:space:]' < /sys/devices/system/cpu/cpufreq/policy$policyId/$node"
        }
        return when (capability) {
            "scheduler.uperf.mode.set" ->
                "grep -qx 'id=uperf' /data/adb/modules/uperf/module.prop && " +
                    "tr -d '[:space:]' < /sdcard/Android/yc/uperf/cur_powermode.txt"
            "scheduler.uperf_gt.mode.set" ->
                "grep -qx 'id=uperf' /data/adb/modules/uperf/module.prop && " +
                    "grep -qx 'name=Uperf Game Turbo' /data/adb/modules/uperf/module.prop && " +
                    "tr -d '[:space:]' < /sdcard/Android/yc/uperf/cur_powermode.txt"
            "scheduler.fas_rs.mode.set" ->
                "grep -qx 'id=fas-rs' /data/adb/modules/fas-rs/module.prop && tr -d '[:space:]' < /dev/fas_rs/mode"
            "scheduler.config_bridge.mode.set" ->
                "grep -qx 'id=Scene_Config_replace' /data/adb/modules/Scene_Config_replace/module.prop && " +
                    "grep -qx 'qijing-scheduler-bridge-v1' /data/adb/modules/Scene_Config_replace/qijing/contract && " +
                    "tr -d '[:space:]' < /data/adb/modules/Scene_Config_replace/qijing/current_mode"
            "scheduler.uperf.probe" -> moduleProbe(
                "/data/adb/modules/uperf/module.prop",
                "/sdcard/Android/yc/uperf/cur_powermode.txt",
                "/data/powercfg.sh"
            )
            "scheduler.uperf_gt.probe" -> moduleProbe(
                "/data/adb/modules/uperf/module.prop",
                "/sdcard/Android/yc/uperf/cur_powermode.txt",
                "/data/powercfg.sh"
            )
            "scheduler.fas_rs.probe" -> moduleProbe(
                "/data/adb/modules/fas-rs/module.prop",
                "/dev/fas_rs/mode",
                "/dev/fas_rs/mode"
            )
            "scheduler.config_bridge.probe" -> configBridgeProbe()
            "cpu.governor.set" -> firstPolicyValue("scaling_governor")
            "cpu.min_frequency.set" -> firstPolicyValue("scaling_min_freq")
            "cpu.max_frequency.set" -> firstPolicyValue("scaling_max_freq")
            "memory.swappiness.set" -> "tr -d '[:space:]' < /proc/sys/vm/swappiness"
            else -> null
        }
    }

    private fun firstPolicyValue(node: String): String =
        "values=\"\$(for file in /sys/devices/system/cpu/cpufreq/policy*/$node; do " +
            "[ -e \"\$file\" ] || continue; tr -d '[:space:]' < \"\$file\" || exit 1; printf '\\n'; done)\" || exit 1; " +
            "[ -n \"\$values\" ] || exit 1; unique=\"\$(printf '%s\\n' \"\$values\" | sort -u)\"; " +
            "[ \"\$(printf '%s\\n' \"\$unique\" | wc -l)\" -eq 1 ] || exit 2; printf '%s' \"\$unique\""

    private fun moduleProbe(moduleProp: String, modePath: String, switchPath: String): String =
        "[ -f '$moduleProp' ] || exit 1; " +
            "id=\"\$(sed -n 's/^id=//p' '$moduleProp' | head -n 1)\"; " +
            "name=\"\$(sed -n 's/^name=//p' '$moduleProp' | head -n 1)\"; " +
            "version=\"\$(sed -n 's/^version=//p' '$moduleProp' | head -n 1)\"; " +
            "mode=\"\$([ -r '$modePath' ] && tr -d '[:space:]' < '$modePath')\"; " +
            "ready=0; [ -e '$switchPath' ] && ready=1; " +
            "printf '%s\\n%s\\n%s\\n%s\\n%s' \"\$id\" \"\$name\" \"\$version\" \"\$mode\" \"\$ready\""

    private fun configBridgeProbe(): String {
        val base = "/data/adb/modules/Scene_Config_replace"
        val moduleProp = "$base/module.prop"
        val bridge = "$base/qijing"
        return "[ -f '$moduleProp' ] || exit 1; " +
            "id=\"\$(sed -n 's/^id=//p' '$moduleProp' | head -n 1)\"; " +
            "name=\"\$(sed -n 's/^name=//p' '$moduleProp' | head -n 1)\"; " +
            "version=\"\$(sed -n 's/^version=//p' '$moduleProp' | head -n 1)\"; " +
            "mode=''; ready=0; " +
            "if [ -r '$bridge/contract' ] && grep -qx 'qijing-scheduler-bridge-v1' '$bridge/contract'; then " +
            "mode=\"\$([ -r '$bridge/current_mode' ] && tr -d '[:space:]' < '$bridge/current_mode')\"; " +
            "[ -x '$bridge/apply-mode' ] && [ -n \"\$mode\" ] && ready=1; fi; " +
            "printf '%s\\n%s\\n%s\\n%s\\n%s' \"\$id\" \"\$name\" \"\$version\" \"\$mode\" \"\$ready\""
    }

    private val POLICY_CAPABILITY = Regex("cpu\\.policy\\.([0-9]{1,3})\\.(governor|min_frequency|max_frequency)\\.set")
}

class UnavailableExecutionBroker(
    private val backend: ExecutionBackend,
    private val reason: String
) : ExecutionBroker, CommandValidator, ExecutionBackendProvider {
    override val executionBackend: ExecutionBackend = backend
    override fun validate(command: CapabilityCommand): ExecutionResult =
        ExecutionResult.Unsupported(command.capability, "${backend.name}: $reason")

    override suspend fun execute(command: CapabilityCommand): ExecutionResult = validate(command)
}
