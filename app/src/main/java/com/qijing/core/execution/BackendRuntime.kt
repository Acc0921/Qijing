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
    val readCommand: (suspend (CapabilityCommand) -> String?)? = null,
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
                BackendRuntime(
                    backend,
                    RootExecutionBroker(transport),
                    readCapability = privilegedReader(transport::execute),
                    readCommand = privilegedCommandReader(transport::execute)
                )
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
        return BackendRuntime(
            ExecutionBackend.SHIZUKU,
            ShizukuExecutionBroker(transport),
            privilegedReader(transport::execute),
            privilegedCommandReader(transport::execute),
            transport
        )
    }

    private fun unavailable(backend: ExecutionBackend, reason: String): BackendRuntime =
        BackendRuntime(backend, UnavailableExecutionBroker(backend, reason))

    private fun privilegedReader(execute: suspend (String) -> String): suspend (String) -> String? = { capability ->
        val command = PrivilegedReadCommandMapper.map(capability)
        if (command == null) null else runCatching { execute(command).trim().ifBlank { null } }.getOrNull()
    }

    private fun privilegedCommandReader(execute: suspend (String) -> String): suspend (CapabilityCommand) -> String? = { capabilityCommand ->
        val command = PrivilegedReadCommandMapper.map(capabilityCommand)
        if (command == null) null else runCatching { execute(command).trim().ifBlank { null } }.getOrNull()
    }
}

/** Fixed read-only templates used to capture rollback values before any privileged write. */
object PrivilegedReadCommandMapper {
    fun map(command: CapabilityCommand): String? = when (val capability = command.capability.removeSuffix(".restore")) {
        "scheduler.node.write" -> mapNodeRead(command)
        "scheduler.profile.limiter.cluster.set" -> mapProfileLimiterClusterRead(command)
        "scheduler.profile.limiter.clear" -> ManagedLimiterRuntime.clearRead()
        "scheduler.profile.gesture_boost.configure" -> mapGestureBoostRead(command)
        "scheduler.profile.gesture_boost.health" -> mapGestureBoostHealth(command)
        "scheduler.profile.limiter.health" -> mapProfileLimiterHealth(command)
        "scheduler.profile.app_frequencies.set" -> mapProfileAppFrequenciesRead(command)
        "scheduler.thread.snapshot" -> mapThreadSnapshot(command)
        "scheduler.thread.cpuset.set",
        "scheduler.thread.affinity.set",
        "scheduler.thread.nice.set",
        "scheduler.thread.policy.set" -> mapThreadPropertyRead(command, capability)
        else -> map(capability)
    }

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
            "display.refresh_rate.set" ->
                "peak=\"\$(settings get system peak_refresh_rate | tr -d '[:space:]')\"; " +
                    "minimum=\"\$(settings get system min_refresh_rate | tr -d '[:space:]')\"; " +
                    "[ \"\$peak\" = 'null' ] && peak=absent; [ \"\$minimum\" = 'null' ] && minimum=absent; " +
                    "printf '%s|%s' \"\$peak\" \"\$minimum\""
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

    private fun mapNodeRead(command: CapabilityCommand): String? {
        if (command.arguments.keys != setOf("path", "value")) return null
        val path = command.arguments["path"] ?: return null
        if (!PrivilegedNodePolicy.validPath(path)) return null
        return "[ -e '$path' ] && tr -d '\\r\\n' < '$path'"
    }

    private fun mapProfileLimiterClusterRead(command: CapabilityCommand): String? {
        val cluster = ProfileLimiterCommandPolicy.parse(command, restore = false) ?: return null
        if (cluster.ddrBoost) return null
        if (ManagedLimiterRuntime.isManaged(cluster)) return ManagedLimiterRuntime.read(cluster)
        val base = "/sys/devices/system/cpu/cpufreq/policy${cluster.policy}"
        val minFile = "$base/scaling_min_freq"
        val maxFile = "$base/scaling_max_freq"
        val coreFile = "/sys/devices/system/cpu/cpu${cluster.policy}/core_ctl/enable"
        val coreRead = if (cluster.coreCtl == "absent") "core=absent" else
            "[ -r '$coreFile' ] && core=\"\$(tr -d '[:space:]' < '$coreFile')\""
        return "[ -r '$minFile' ] && [ -r '$maxFile' ] && " +
            "min=\"\$(tr -d '[:space:]' < '$minFile')\" && max=\"\$(tr -d '[:space:]' < '$maxFile')\" && " +
            "$coreRead && printf '%s|%s|%s' \"\$min\" \"\$max\" \"\$core\""
    }

    private fun mapProfileAppFrequenciesRead(command: CapabilityCommand): String? {
        if (ProfileAppFrequencyCommandPolicy.parse(command, restore = false) == null) return null
        return "base=/sys/devices/system/cpu/cpufreq; policies=\"\$(for path in \"\$base\"/policy[0-9]*; do " +
            "[ -d \"\$path\" ] || continue; id=\"\${path##*policy}\"; " +
            "case \"\$id\" in ''|*[!0-9]*) continue;; esac; [ \"\$id\" -le 255 ] || continue; " +
            "printf '%s\\n' \"\$id\"; done | sort -n -u)\" && " +
            "efficiency=\"\$(printf '%s\\n' \"\$policies\" | head -n 1)\" && " +
            "performance=\"\$(printf '%s\\n' \"\$policies\" | tail -n 1)\" && " +
            "[ -n \"\$efficiency\" ] && [ -n \"\$performance\" ] && [ \"\$efficiency\" != \"\$performance\" ] && " +
            "efficiency_max=\"\$(tr -d '[:space:]' < \"\$base/policy\$efficiency/scaling_max_freq\")\" && " +
            "performance_max=\"\$(tr -d '[:space:]' < \"\$base/policy\$performance/scaling_max_freq\")\" && " +
            "printf '%s|%s|%s|%s' \"\$efficiency\" \"\$efficiency_max\" \"\$performance\" \"\$performance_max\""
    }

    private fun mapGestureBoostRead(command: CapabilityCommand): String? {
        val contract = ManagedGestureCommandPolicy.parse(command, restore = false) ?: return null
        return ManagedGestureRuntime.read(contract)
    }

    private fun mapGestureBoostHealth(command: CapabilityCommand): String? {
        val contract = ManagedGestureCommandPolicy.parse(command, restore = false) ?: return null
        return ManagedGestureRuntime.health(contract)
    }

    private fun mapProfileLimiterHealth(command: CapabilityCommand): String? {
        val cluster = ProfileLimiterCommandPolicy.parse(command, restore = false) ?: return null
        if (!ManagedLimiterRuntime.isManaged(cluster) || cluster.ddrBoost) return null
        return ManagedLimiterRuntime.health(cluster)
    }

    private fun mapThreadSnapshot(command: CapabilityCommand): String? {
        if (command.arguments.keys != setOf("package")) return null
        val packageName = command.arguments["package"]?.takeIf { THREAD_PACKAGE.matches(it) } ?: return null
        return "pkg='$packageName'; for proc in /proc/[0-9]*; do pid=\"\${proc##*/}\"; " +
            "cmd=\"\$(tr '\\000' '\\n' < \"\$proc/cmdline\" 2>/dev/null | head -n 1)\"; " +
            "case \"\$cmd\" in \"\$pkg\"|\"\$pkg\":*) ;; *) continue ;; esac; " +
            "ps=\"\$(sed 's/^.*) //' \"\$proc/stat\" 2>/dev/null | awk '{print \$20}')\"; [ -n \"\$ps\" ] || continue; " +
            "for task in \"\$proc\"/task/[0-9]*; do tid=\"\${task##*/}\"; " +
            "ts=\"\$(sed 's/^.*) //' \"\$task/stat\" 2>/dev/null | awk '{print \$20}')\"; " +
            "nice=\"\$(sed 's/^.*) //' \"\$task/stat\" 2>/dev/null | awk '{print \$17}')\"; " +
            "name=\"\$(tr '|\\r\\n' '___' < \"\$task/comm\" 2>/dev/null)\"; " +
            "aff=\"\$(awk '/^Cpus_allowed_list:/{print \$2}' \"\$task/status\" 2>/dev/null)\"; " +
            "grp=\"\$(cat \"\$task/cpuset\" 2>/dev/null)\"; [ -n \"\$grp\" ] || grp='/'; " +
            "gcpus=\"\$(cat \"/dev/cpuset\$grp/cpus\" 2>/dev/null)\"; [ -n \"\$gcpus\" ] || gcpus=\"\$aff\"; " +
            "pol=\"\$(sed 's/^.*) //' \"\$task/stat\" 2>/dev/null | awk '{print \$39}')\"; " +
            "prio=\"\$(sed 's/^.*) //' \"\$task/stat\" 2>/dev/null | awk '{print \$38}')\"; " +
            "[ -n \"\$ts\" ] && [ -n \"\$nice\" ] && [ -n \"\$name\" ] && [ -n \"\$aff\" ] && [ -n \"\$pol\" ] && [ -n \"\$prio\" ] || continue; " +
            "printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' \"\$pid\" \"\$ps\" \"\$tid\" \"\$ts\" \"\$name\" \"\$aff\" \"\$grp\" \"\$gcpus\" \"\$pol\" \"\$nice\" \"\$prio\"; done; done"
    }

    private fun mapThreadPropertyRead(command: CapabilityCommand, capability: String): String? {
        val identity = ThreadCommandPolicy.parse(command) ?: return null
        val prefix = ThreadCommandPolicy.identityShell(identity)
        val property = when (capability) {
            "scheduler.thread.cpuset.set" -> "cat '/proc/${identity.pid}/task/${identity.tid}/cpuset'"
            "scheduler.thread.affinity.set" ->
                "taskset -p '${identity.tid}' | sed -n 's/.*: //p' | tr 'A-F' 'a-f'"
            "scheduler.thread.nice.set" ->
                "sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$17}'"
            "scheduler.thread.policy.set" ->
                "p=\"\$(sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$39}')\"; " +
                    "r=\"\$(sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$38}')\"; " +
                    "case \"\$p\" in 0) n=OTHER;; 1) n=FIFO;; 2) n=ROUND_ROBIN;; 3) n=BATCH;; 5) n=IDLE;; 6) n=DEADLINE;; *) exit 4;; esac; printf '%s:%s' \"\$n\" \"\$r\""
            else -> return null
        }
        return "$prefix && $property"
    }

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
    private val THREAD_PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
}

internal data class ThreadCommandIdentity(
    val packageName: String,
    val pid: Int,
    val processStart: Long,
    val tid: Int,
    val threadStart: Long,
    val expected: String,
    val value: String
)

internal object ThreadCommandPolicy {
    private val PACKAGE = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val KEYS = setOf("package", "pid", "process_start", "tid", "start_ticks", "expected", "value")

    fun parse(command: CapabilityCommand): ThreadCommandIdentity? {
        if (command.arguments.keys != KEYS) return null
        val packageName = command.arguments["package"]?.takeIf { PACKAGE.matches(it) } ?: return null
        val pid = command.arguments["pid"]?.toIntOrNull()?.takeIf { it in 1..4_194_304 } ?: return null
        val processStart = command.arguments["process_start"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val tid = command.arguments["tid"]?.toIntOrNull()?.takeIf { it in 1..4_194_304 } ?: return null
        val threadStart = command.arguments["start_ticks"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val expected = command.arguments["expected"]?.takeIf { it.length in 1..256 && '\u0000' !in it } ?: return null
        val value = command.arguments["value"]?.takeIf { it.length in 1..256 && '\u0000' !in it } ?: return null
        return ThreadCommandIdentity(packageName, pid, processStart, tid, threadStart, expected, value)
    }

    fun identityShell(identity: ThreadCommandIdentity): String =
        "[ -r '/proc/${identity.pid}/stat' ] && [ -r '/proc/${identity.pid}/task/${identity.tid}/stat' ] && " +
            "cmd=\"\$(tr '\\000' '\\n' < '/proc/${identity.pid}/cmdline' | head -n 1)\"; " +
            "case \"\$cmd\" in '${identity.packageName}'|'${identity.packageName}':*) true;; *) false;; esac && " +
            "[ \"\$(sed 's/^.*) //' '/proc/${identity.pid}/stat' | awk '{print \$20}')\" = '${identity.processStart}' ] && " +
            "[ \"\$(sed 's/^.*) //' '/proc/${identity.pid}/task/${identity.tid}/stat' | awk '{print \$20}')\" = '${identity.threadStart}' ]"
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
