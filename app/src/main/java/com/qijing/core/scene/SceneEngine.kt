package com.qijing.core.scene

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.CommandPlan
import com.qijing.core.execution.CommandValidator
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.execution.ExecutionBackendProvider
import com.qijing.core.execution.RequiresRollbackSnapshot
import com.qijing.core.execution.TransactionResult
import com.qijing.core.logging.TaskLog
import com.qijing.core.logging.TaskLogStore
import com.qijing.core.model.SceneProfile
import java.util.UUID

class SceneEngine(
    private val broker: ExecutionBroker,
    private val logs: TaskLogStore,
    private val snapshots: SceneSnapshotManager? = null,
    private val journalStore: SceneTransactionJournalStore? = null,
    private val events: SceneTaskEventStore? = null
) {
    /** Builds and validates a plan without performing any writes. */
    suspend fun prepare(
        scene: SceneProfile,
        recordFailureLog: Boolean = true,
        taskId: String = UUID.randomUUID().toString()
    ): ScenePreparation {
        val rawCommands = buildCommands(scene)
        val backend = (broker as? ExecutionBackendProvider)?.executionBackend
        val rawPlan = CommandPlan(taskId, rawCommands)
        if (recordFailureLog) recordEvent(scene, taskId, backend, SceneTaskPhase.PREFLIGHT, "检查能力白名单、参数和恢复条件")
        if (rawCommands.isEmpty()) {
            val failure = ExecutionResult.Failed("SCENE_NO_WRITABLE_INTENT", "场景没有可执行的调节目标，未执行任何写入")
            recordPreparationFailure(taskId, "preflight", failure, recordFailureLog)
            return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
        }
        (broker as? CommandValidator)?.let { validator ->
            rawCommands.firstNotNullOfOrNull(validator::validate)?.let { failure ->
                recordPreparationFailure(taskId, "preflight", failure, recordFailureLog)
                return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
            }
        }
        if (broker is RequiresRollbackSnapshot && snapshots == null) {
            val failure = ExecutionResult.Failed("SNAPSHOT_UNAVAILABLE", "真实执行后端未提供原值读取能力，未执行任何写入")
            recordPreparationFailure(taskId, "snapshot", failure, recordFailureLog)
            return ScenePreparation(scene, backend, rawPlan, snapshot = null, failure)
        }
        val snapshot = snapshots?.capture(rawCommands)
        val commands = if (snapshot == null) rawCommands else orderFrequencyCommands(
            snapshots.attachRestore(rawCommands, snapshot),
            snapshot
        )
        val plan = CommandPlan(taskId, commands)
        if (snapshots != null && broker is CommandValidator) {
            commands.firstOrNull { it.rollback == null }?.let { missing ->
                val failure = ExecutionResult.Failed("SNAPSHOT_INCOMPLETE", "无法读取 ${missing.capability} 的原值，未执行任何写入")
                recordPreparationFailure(taskId, "snapshot", failure, recordFailureLog)
                return ScenePreparation(scene, backend, plan, snapshot, failure)
            }
            commands.mapNotNull { it.rollback }.firstNotNullOfOrNull(broker::validate)?.let { failure ->
                recordPreparationFailure(taskId, "snapshot-validation", failure, recordFailureLog)
                return ScenePreparation(scene, backend, plan, snapshot, failure)
            }
        }
        if (recordFailureLog && snapshot != null) {
            recordEvent(scene, taskId, backend, SceneTaskPhase.SNAPSHOT, "原值快照与恢复命令已生成")
        }
        return ScenePreparation(scene, backend, plan, snapshot)
    }

    suspend fun apply(scene: SceneProfile, matchDetail: String = "前台应用命中场景"): TransactionResult {
        // Always prepare again at execution time so a UI preview can never supply a stale snapshot.
        val taskId = UUID.randomUUID().toString()
        val reportedBackend = (broker as? ExecutionBackendProvider)?.executionBackend
        recordEvent(scene, taskId, reportedBackend, SceneTaskPhase.MATCHED, matchDetail)
        val preparation = prepare(scene, recordFailureLog = true, taskId = taskId)
        preparation.failure?.let {
            recordEvent(scene, taskId, preparation.backend, SceneTaskPhase.FAILED, it.toString())
            return TransactionResult(preparation.plan, emptyList(), it)
        }
        val plan = preparation.plan
        val backend = preparation.backend
        if (backend == null) {
            val failure = ExecutionResult.Failed("BACKEND_UNDECLARED", "执行后端未声明身份，已阻止首条命令")
            recordEvent(scene, plan.id, null, SceneTaskPhase.FAILED, failure.message)
            return TransactionResult(plan, emptyList(), failure)
        }
        if (backend !in setOf(
                com.qijing.core.model.ExecutionBackend.DRY_RUN,
                com.qijing.core.model.ExecutionBackend.ROOT,
                com.qijing.core.model.ExecutionBackend.SHIZUKU
            )) {
            val failure = ExecutionResult.Failed("BACKEND_NOT_ALLOWED", "场景执行不允许使用 $backend 后端")
            recordEvent(scene, plan.id, backend, SceneTaskPhase.FAILED, failure.message)
            return TransactionResult(plan, emptyList(), failure)
        }
        val journalSession = if (backend in setOf(
                com.qijing.core.model.ExecutionBackend.ROOT,
                com.qijing.core.model.ExecutionBackend.SHIZUKU
            )) {
            val durableStore = journalStore ?: run {
                val failure = ExecutionResult.Failed("JOURNAL_NOT_READY", "真实执行缺少持久化恢复 journal，未执行任何写入")
                recordEvent(scene, plan.id, backend, SceneTaskPhase.FAILED, failure.message)
                return TransactionResult(plan, emptyList(), failure)
            }
            val session = SceneJournalSession.open(durableStore, scene, plan, backend!!)
            if (session == null) {
                val failure = ExecutionResult.Failed("JOURNAL_NOT_READY", "无法在写入前持久化恢复 journal，未执行任何写入")
                recordEvent(scene, plan.id, backend, SceneTaskPhase.FAILED, failure.message)
                return TransactionResult(plan, emptyList(), failure)
            }
            session
        } else null
        val appliedCommands = mutableListOf<Triple<Int, CapabilityCommand, ExecutionResult.Applied>>()
        for ((index, command) in plan.commands.withIndex()) {
            if (journalSession != null && !journalSession.markWriteStarted(index)) {
                val failure = ExecutionResult.Failed("JOURNAL_WRITE_FAILED", "无法保存写入前状态，已阻止后续写入")
                val rolledBack = rollbackApplied(plan.id, appliedCommands, journalSession)
                val recoveryClosed = rolledBack && journalSession.clear()
                recordEvent(
                    scene,
                    plan.id,
                    backend,
                    if (recoveryClosed) SceneTaskPhase.RESTORED else SceneTaskPhase.RECOVERY_REQUIRED,
                    if (recoveryClosed) "写入前状态保存失败；已恢复先前写入并关闭 journal" else "写入前状态保存失败；恢复或 journal 清理未完成"
                )
                return TransactionResult(plan, appliedCommands.map { it.third }, failure, recoveryClosed)
            }
            recordEvent(scene, plan.id, backend, SceneTaskPhase.APPLYING, "${index + 1}/${plan.commands.size} · ${command.capability}")
            val reportedResult = broker.execute(command)
            val result = if (reportedResult is ExecutionResult.Applied && reportedResult.backend != backend) {
                ExecutionResult.Failed(
                    "EXECUTION_BACKEND_MISMATCH",
                    "执行结果后端为 ${reportedResult.backend}，预期 $backend",
                    command.rollback
                )
            } else reportedResult
            val previewed = result is ExecutionResult.Applied && result.backend == com.qijing.core.model.ExecutionBackend.DRY_RUN
            logs.append(
                TaskLog(
                    plan.id,
                    if (previewed) "preview:${command.capability}" else command.capability,
                    if (previewed) "预演完成，未修改系统" else result.toString(),
                    result is ExecutionResult.Applied,
                    System.currentTimeMillis()
                )
            )
            if (result is ExecutionResult.Applied) {
                appliedCommands += Triple(index, command, result)
                recordEvent(
                    scene,
                    plan.id,
                    backend,
                    if (previewed) SceneTaskPhase.PREVIEWED else SceneTaskPhase.VERIFIED,
                    if (previewed) "${command.capability} 仅完成预演" else "${command.capability} 写后读回一致"
                )
                if (journalSession != null && !journalSession.markApplied(index)) {
                    val failure = ExecutionResult.Failed("JOURNAL_PROGRESS_FAILED", "写入完成但无法保存 journal 进度，立即尝试恢复")
                    val rolledBack = rollbackApplied(plan.id, appliedCommands, journalSession)
                    val recoveryClosed = rolledBack && journalSession.clear()
                    recordEvent(
                        scene,
                        plan.id,
                        backend,
                        if (recoveryClosed) SceneTaskPhase.RESTORED else SceneTaskPhase.RECOVERY_REQUIRED,
                        if (recoveryClosed) "journal 进度保存失败；已恢复原值并关闭 journal" else failure.message
                    )
                    return TransactionResult(plan, appliedCommands.map { it.third }, failure, recoveryClosed)
                }
                continue
            }
            recordEvent(scene, plan.id, backend, SceneTaskPhase.FAILED, result.toString())
            var rolledBack = true
            var rollbackAttempted = false
            val rollbackCommands = buildList<Pair<Int, CapabilityCommand>> {
                command.rollback?.let { add(index to it) }
                appliedCommands.asReversed().mapNotNullTo(this) { (appliedIndex, appliedCommand, _) ->
                    appliedCommand.rollback?.let { appliedIndex to it }
                }
            }
            if (command.rollback == null || rollbackCommands.size < appliedCommands.size + 1) rolledBack = false
            rollbackCommands.forEach { (rollbackIndex, rollback) ->
                rollbackAttempted = true
                val rollbackResult = broker.execute(rollback)
                val backendMatches = rollbackResult is ExecutionResult.Applied && rollbackResult.backend == backend
                val progressSaved = !backendMatches || journalSession == null || journalSession.markRestored(rollbackIndex)
                val ok = backendMatches && progressSaved
                rolledBack = rolledBack && ok
                logs.append(TaskLog(plan.id, "rollback:${rollback.capability}", rollbackResult.toString(), ok, System.currentTimeMillis()))
            }
            val completedRollback = rolledBack && (rollbackAttempted || appliedCommands.isEmpty())
            val recoveryClosed = completedRollback && (journalSession?.clear() ?: true)
            if (recoveryClosed) {
                recordEvent(scene, plan.id, backend, SceneTaskPhase.RESTORED, "执行失败后已恢复原值")
            } else {
                recordEvent(scene, plan.id, backend, SceneTaskPhase.RECOVERY_REQUIRED, "执行失败，恢复或 journal 清理不完整")
            }
            return TransactionResult(plan, appliedCommands.map { it.third }, result, recoveryClosed)
        }
        val previewOnly = appliedCommands.isNotEmpty() && appliedCommands.all {
            it.third.backend == com.qijing.core.model.ExecutionBackend.DRY_RUN
        }
        recordEvent(
            scene,
            plan.id,
            backend,
            if (previewOnly) SceneTaskPhase.PREVIEWED else SceneTaskPhase.ACTIVE,
            if (previewOnly) "预览命中完成，系统未修改，无需恢复" else "场景已验证生效，等待离场恢复"
        )
        return TransactionResult(plan, appliedCommands.map { it.third })
    }

    private suspend fun rollbackApplied(
        taskId: String,
        appliedCommands: List<Triple<Int, CapabilityCommand, ExecutionResult.Applied>>,
        journalSession: SceneJournalSession?
    ): Boolean {
        var complete = true
        appliedCommands.asReversed().forEach { (index, command, _) ->
            val rollback = command.rollback
            if (rollback == null) {
                complete = false
                return@forEach
            }
            val result = broker.execute(rollback)
            val expectedBackend = appliedCommands.firstOrNull()?.third?.backend
            val backendMatches = result is ExecutionResult.Applied && result.backend == expectedBackend
            val progressSaved = !backendMatches || journalSession == null || journalSession.markRestored(index)
            val ok = backendMatches && progressSaved
            complete = complete && ok
            logs.append(TaskLog(taskId, "rollback:${rollback.capability}", result.toString(), ok, System.currentTimeMillis()))
        }
        return complete
    }

    private fun recordEvent(
        scene: SceneProfile,
        taskId: String,
        backend: com.qijing.core.model.ExecutionBackend?,
        phase: SceneTaskPhase,
        detail: String
    ) {
        events?.append(
            SceneTaskEvent(
                taskId = taskId,
                sceneId = scene.id,
                sceneName = scene.name,
                packageName = scene.packageNames.firstOrNull(),
                backend = backend,
                phase = phase,
                detail = detail
            )
        )
    }

    private fun recordPreparationFailure(
        taskId: String,
        stage: String,
        failure: ExecutionResult,
        enabled: Boolean
    ) {
        if (enabled) logs.append(TaskLog(taskId, stage, failure.toString(), false, System.currentTimeMillis()))
    }

    private fun buildCommands(scene: SceneProfile): List<CapabilityCommand> = buildList {
        if (scene.schedulerProvider != com.qijing.core.scheduler.SchedulerProviderId.SYSTEM) {
            val mode = scene.schedulerMode ?: return@buildList
            val capability = when (scene.schedulerProvider) {
                com.qijing.core.scheduler.SchedulerProviderId.UPERF -> "scheduler.uperf.mode.set"
                com.qijing.core.scheduler.SchedulerProviderId.UPERF_GT -> "scheduler.uperf_gt.mode.set"
                com.qijing.core.scheduler.SchedulerProviderId.FAS_RS -> "scheduler.fas_rs.mode.set"
                com.qijing.core.scheduler.SchedulerProviderId.CONFIG_BRIDGE -> "scheduler.config_bridge.mode.set"
                com.qijing.core.scheduler.SchedulerProviderId.SYSTEM -> return@buildList
            }
            add(CapabilityCommand(capability, mapOf("value" to mode.stableId)))
            return@buildList
        }
        scene.cpu.governor?.let { add(CapabilityCommand("cpu.governor.set", mapOf("value" to it))) }
        scene.cpu.minFrequencyKHz?.let { add(CapabilityCommand("cpu.min_frequency.set", mapOf("khz" to it.toString()))) }
        scene.cpu.maxFrequencyKHz?.let { add(CapabilityCommand("cpu.max_frequency.set", mapOf("khz" to it.toString()))) }
        scene.cpu.policies.sortedBy { it.policyId }.forEach { policy ->
            val prefix = "cpu.policy.${policy.policyId}"
            policy.governor?.let { add(CapabilityCommand("$prefix.governor.set", mapOf("value" to it))) }
            policy.minFrequencyKHz?.let { add(CapabilityCommand("$prefix.min_frequency.set", mapOf("khz" to it.toString()))) }
            policy.maxFrequencyKHz?.let { add(CapabilityCommand("$prefix.max_frequency.set", mapOf("khz" to it.toString()))) }
        }
        scene.cpu.onlineCores?.let { add(CapabilityCommand("cpu.online_cores.set", mapOf("value" to it.sorted().joinToString(",")))) }
        scene.memory.zramEnabled?.let { add(CapabilityCommand("memory.zram.enabled", mapOf("value" to it.toString()))) }
        scene.memory.zramSizeBytes?.let { add(CapabilityCommand("memory.zram.size", mapOf("bytes" to it.toString()))) }
        scene.memory.compressionAlgorithm?.let { add(CapabilityCommand("memory.zram.algorithm.set", mapOf("value" to it))) }
        scene.memory.swappiness?.let { add(CapabilityCommand("memory.swappiness.set", mapOf("value" to it.toString()))) }
    }

    /** Avoids transient min/max inversions when a policy range moves above or below its old range. */
    private fun orderFrequencyCommands(
        commands: List<CapabilityCommand>,
        snapshot: SceneSnapshot
    ): List<CapabilityCommand> {
        val result = commands.toMutableList()
        val prefixes = commands.mapNotNull { command ->
            when {
                command.capability == "cpu.min_frequency.set" || command.capability == "cpu.max_frequency.set" -> "cpu"
                POLICY_FREQUENCY.matches(command.capability) -> command.capability.substringBeforeLast('.')
                    .substringBeforeLast('.')
                else -> null
            }
        }.distinct()
        prefixes.forEach { prefix ->
            val minCapability = if (prefix == "cpu") "cpu.min_frequency.set" else "$prefix.min_frequency.set"
            val maxCapability = if (prefix == "cpu") "cpu.max_frequency.set" else "$prefix.max_frequency.set"
            val minIndex = result.indexOfFirst { it.capability == minCapability }
            val maxIndex = result.indexOfFirst { it.capability == maxCapability }
            if (minIndex < 0 || maxIndex < 0) return@forEach
            val targetMin = result[minIndex].arguments["khz"]?.toLongOrNull() ?: return@forEach
            val currentMax = snapshot.values[maxCapability]?.toLongOrNull() ?: return@forEach
            val maxFirst = targetMin > currentMax
            if (maxFirst && maxIndex > minIndex) {
                val maxCommand = result.removeAt(maxIndex)
                result.add(minIndex, maxCommand)
            }
        }
        return result
    }

    private companion object {
        val POLICY_FREQUENCY = Regex("cpu\\.policy\\.[0-9]{1,3}\\.(min_frequency|max_frequency)\\.set")
    }
}
