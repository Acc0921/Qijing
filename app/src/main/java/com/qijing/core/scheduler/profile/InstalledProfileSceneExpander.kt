package com.qijing.core.scheduler.profile

import android.content.Context
import android.os.SystemClock
import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.model.ExecutionBackend
import com.qijing.core.model.SceneProfile
import com.qijing.core.scene.SceneCommandExpansion
import com.qijing.core.scene.SceneCommandExpander
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import com.qijing.core.scheduler.ThreadPlacementJsonParser
import com.qijing.core.scheduler.ThreadPlacementLoad
import com.qijing.core.scheduler.thread.ThreadRuntimeBridge
import com.qijing.core.scheduler.thread.ThreadRuntimePlan
import com.qijing.core.scheduler.pack.AndroidSchedulerDeviceProbe
import com.qijing.core.scheduler.pack.SchedulerPackLoad
import com.qijing.core.scheduler.pack.SchedulerPackStore
import java.io.File

class InstalledProfileSceneExpander(
    context: Context,
    private val commandReader: (suspend (CapabilityCommand) -> String?)? = null,
    private val enableThreadRuntime: Boolean = false,
    private val phaseProvider: () -> ProfilePhase = { ProfilePhase.ACTIVE },
    private val executionBackend: ExecutionBackend = if (enableThreadRuntime) ExecutionBackend.ROOT else ExecutionBackend.DRY_RUN
) : SceneCommandExpander {
    private val store = SchedulerPackStore(context.applicationContext)
    private val compiler = ProfileCompiler()
    private val planner = ProfileCommandPlanner()
    private val fasPlanner = ImportedFasCommandPlanner()
    private val gestureBoostPlanner = GestureBoostContractPlanner(planner)

    override suspend fun expand(scene: SceneProfile): SceneCommandExpansion {
        if (scene.schedulerProvider != SchedulerProviderId.QIJING_PROFILE) {
            return SceneCommandExpansion.Commands(emptyList())
        }
        val mode = scene.schedulerMode?.toProfileMode()
            ?: return blocked("PROFILE_MODE_MISSING", "栖境配置引擎需要选择省电、均衡、性能或极速模式")
        val installed = when (val loaded = store.load()) {
            SchedulerPackLoad.None -> return blocked("PROFILE_PACK_MISSING", "尚未导入配置包")
            is SchedulerPackLoad.Corrupt -> return blocked("PROFILE_PACK_CORRUPT", loaded.reason)
            is SchedulerPackLoad.Loaded -> loaded.value
        }
        val variant = installed.selectedVariant
            ?: return blocked("PROFILE_VARIANT_MISSING", "配置包尚未选择与设备匹配的变体")
        val device = AndroidSchedulerDeviceProbe.probe()
        val compatibility = variant.compatibilityWith(device)
        if (!compatibility.compatible) {
            return blocked("PROFILE_DEVICE_MISMATCH", "配置与当前 SoC/CPU 拓扑不匹配：${compatibility.mismatches.joinToString()}")
        }
        val program = when (val compiled = compiler.compile(variant.profileJson, variant.imports)) {
            is ProfileCompileResult.Rejected -> return blocked("PROFILE_COMPILE_REJECTED", compiled.reason)
            is ProfileCompileResult.Compiled -> compiled.program
        }
        val packageName = scene.packageNames.firstOrNull().orEmpty()
        val workload = resolveWorkload(variant.threadsJson, device.cpuCores, packageName)
        val imported = selectApplicationRule(program, workload, packageName)?.modes?.get(mode)
        val phase = phaseProvider()
        val operations = buildList {
            addAll(program.reset)
            addAll(program.routes[ProfileRoute(mode, workload, phase)].orEmpty())
            addAll(imported?.call.orEmpty())
            addAll(if (phase == ProfilePhase.ACTIVE) imported?.active.orEmpty() else imported?.inactive.orEmpty())
        }
        if (operations.isEmpty()) return blocked("PROFILE_ROUTE_EMPTY", "所选模式没有可执行路由")
        val policyIds = variant.hardware.topology.clusterCoreCounts.runningFold(0, Int::plus).dropLast(1)
        val frequencyTables = policyIds.associateWith(::readFrequencyTable)
        if (frequencyTables.any { it.value.isEmpty() }) {
            return blocked("PROFILE_FREQUENCY_TABLE_MISSING", "无法读取完整 CPU 频率表，已阻止配置执行")
        }
        val binding = ProfileDeviceBinding(policyIds, frequencyTables)
        return when (val planned = planner.plan(program, operations, binding)) {
            is ProfileCommandPlan.Rejected -> blocked(planned.code, planned.reason)
            is ProfileCommandPlan.Planned -> {
                val gestureCommand = when (val gestureBoost = gestureBoostPlanner.plan(program, binding)) {
                    is GestureBoostContractPlan.Rejected -> return blocked(gestureBoost.code, gestureBoost.reason)
                    is GestureBoostContractPlan.Configured -> if (executionBackend == ExecutionBackend.SHIZUKU) {
                        return blocked("GESTURE_BOOST_ROOT_REQUIRED", "该配置包含触摸瞬时调节，Shizuku 不具备常驻 Root watcher 能力")
                    } else gestureBoost.contract.configureCommand
                    GestureBoostContractPlan.AbsentOrDisabled,
                    GestureBoostContractPlan.NoOp -> null
                }
                val fasPlan = when (val result = fasPlanner.plan(imported?.metadata.orEmpty(), packageName, binding)) {
                    is ProfileCommandPlan.Rejected -> return blocked(result.code, result.reason)
                    is ProfileCommandPlan.Planned -> result
                }
                val threadCommands = if (phase == ProfilePhase.ACTIVE) {
                    planThreads(variant.threadsJson, device.cpuCores, packageName)
                } else ThreadRuntimePlan.Commands(emptyList())
                if (threadCommands is ThreadRuntimePlan.Rejected) {
                    blocked("THREAD_PLAN_REJECTED", threadCommands.reason)
                } else SceneCommandExpansion.Commands(
                    planned.commands + fasPlan.commands + (threadCommands as ThreadRuntimePlan.Commands).commands + listOfNotNull(gestureCommand)
                )
            }
        }
    }

    private suspend fun planThreads(
        threadsJson: String,
        cores: Set<Int>,
        packageName: String
    ): ThreadRuntimePlan {
        if (!enableThreadRuntime || packageName.isBlank()) return ThreadRuntimePlan.Commands(emptyList())
        val reader = commandReader ?: return ThreadRuntimePlan.Rejected("Root 线程读取通道不可用")
        val rules = when (val loaded = ThreadPlacementJsonParser().parse(threadsJson, cores)) {
            is ThreadPlacementLoad.Rejected -> return ThreadRuntimePlan.Rejected(loaded.reason)
            is ThreadPlacementLoad.Loaded -> loaded.ruleSet
        }
        if (rules.profiles.none { packageName in it.packageNames }) return ThreadRuntimePlan.Commands(emptyList())
        val raw = reader(CapabilityCommand("scheduler.thread.snapshot", mapOf("package" to packageName)))
            ?: return ThreadRuntimePlan.Rejected("无法读取 $packageName 的 Root 线程快照")
        return ThreadRuntimeBridge().parseAndPlan(packageName, cores, rules, raw, SystemClock.elapsedRealtime())
    }

    private fun resolveWorkload(threadsJson: String, cores: Set<Int>, packageName: String): ProfileWorkload {
        val rules = ThreadPlacementJsonParser().parse(threadsJson, cores)
        val knownGame = (rules as? ThreadPlacementLoad.Loaded)?.ruleSet?.profiles
            ?.any { packageName in it.packageNames } == true
        return if (knownGame) ProfileWorkload.GAME else ProfileWorkload.APP
    }

    private fun selectApplicationRule(
        program: CompiledProfileProgram,
        workload: ProfileWorkload,
        packageName: String
    ): ImportedApplicationRule? {
        val candidates = program.applicationRules.filter { it.workload == workload }
        return candidates.firstOrNull { rule ->
            rule.packageSelectors.any { selector ->
                selector.equals(packageName, ignoreCase = true) ||
                    (selector.equals("Camera", ignoreCase = true) && packageName.contains("camera", ignoreCase = true))
            }
        } ?: candidates.firstOrNull { "*" in it.packageSelectors }
    }

    private fun SchedulerMode.toProfileMode(): ProfileMode = when (this) {
        SchedulerMode.POWER_SAVE -> ProfileMode.POWER_SAVE
        SchedulerMode.BALANCED -> ProfileMode.BALANCED
        SchedulerMode.PERFORMANCE -> ProfileMode.PERFORMANCE
        SchedulerMode.EXTREME -> ProfileMode.FAST
    }

    private fun readFrequencyTable(policyId: Int): List<Long> {
        val candidates = listOf(
            "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_available_frequencies",
            "/sys/devices/system/cpu/cpu$policyId/cpufreq/scaling_available_frequencies"
        )
        return candidates.firstNotNullOfOrNull { path ->
            runCatching {
                File(path).readText().trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
                    .filter { it > 0 }.distinct().sorted().takeIf(List<Long>::isNotEmpty)
            }.getOrNull()
        }.orEmpty()
    }

    private fun blocked(code: String, reason: String) = SceneCommandExpansion.Blocked(code, reason)
}
