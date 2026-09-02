package com.qijing.core.scheduler.thread

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.scheduler.CpuSet
import com.qijing.core.scheduler.ThreadPlacementRuleSet
import java.math.BigInteger

sealed interface ThreadRuntimePlan {
    data class Commands(val commands: List<CapabilityCommand>) : ThreadRuntimePlan
    data class Rejected(val reason: String) : ThreadRuntimePlan
}

class ThreadRuntimeBridge {
    fun parseAndPlan(
        packageName: String,
        availableCores: Set<Int>,
        ruleSet: ThreadPlacementRuleSet,
        rawSnapshot: String,
        capturedAtElapsedRealtimeMs: Long
    ): ThreadRuntimePlan {
        val snapshot = parseSnapshot(packageName, availableCores, rawSnapshot, capturedAtElapsedRealtimeMs)
            ?: return ThreadRuntimePlan.Rejected("Root 线程快照不完整或格式无效")
        return when (val result = ThreadPlacementPlanner().plan(ruleSet, snapshot)) {
            is ThreadPlacementPlanResult.Rejected -> ThreadRuntimePlan.Rejected(
                result.reasons.joinToString("；") { "${it.code}:${it.message}" }
            )
            is ThreadPlacementPlanResult.Planned -> ThreadRuntimePlan.Commands(
                result.plan.applyMutations.map(::toCommand)
            )
        }
    }

    private fun parseSnapshot(
        packageName: String,
        availableCores: Set<Int>,
        raw: String,
        capturedAt: Long
    ): RunningProcessSnapshot? {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) return null
        if (raw.isBlank()) return RunningProcessSnapshot(capturedAt, availableCores, emptyList())
        val threads = mutableListOf<Observed>()
        for (line in raw.lineSequence().filter(String::isNotBlank)) {
            val fields = line.split('|')
            if (fields.size != 11) return null
            val pid = fields[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val processStart = fields[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val tid = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val threadStart = fields[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val name = fields[4].takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            val affinity = CpuSet.parse(fields[5], availableCores) ?: return null
            val group = fields[6].takeIf(::validGroup) ?: return null
            val groupCpus = CpuSet.parse(fields[7], availableCores) ?: affinity
            val policy = fields[8].toIntOrNull()?.toPolicy() ?: return null
            val nice = fields[9].toIntOrNull()?.takeIf { it in -20..19 } ?: return null
            val priority = fields[10].toIntOrNull()?.takeIf { it in 0..99 } ?: return null
            threads += Observed(pid, processStart, RunningThread(
                tid = tid,
                startTimeTicks = threadStart,
                name = name,
                affinity = affinity,
                cpuSetPlacement = observedCpuSetPlacement(group, groupCpus),
                scheduling = ThreadSchedulingState(policy, priority),
                nice = nice
            ))
        }
        if (threads.size > MAX_THREADS) return null
        val processes = threads.groupBy { it.pid to it.processStart }.map { (identity, observed) ->
            RunningProcess(identity.first, identity.second, packageName, observed.map(Observed::thread))
        }
        return RunningProcessSnapshot(capturedAt, availableCores, processes)
    }

    private fun toCommand(mutation: ThreadPlacementMutation): CapabilityCommand {
        val identity = mutation.identity
        val common = mapOf(
            "package" to identity.process.packageName,
            "pid" to identity.process.pid.toString(),
            "process_start" to identity.process.startTimeTicks.toString(),
            "tid" to identity.tid.toString(),
            "start_ticks" to identity.startTimeTicks.toString()
        )
        return when (mutation) {
            is ThreadPlacementMutation.SetCpuSet -> CapabilityCommand(
                "scheduler.thread.cpuset.set",
                common + mapOf(
                    "expected" to mutation.expected.groupId,
                    "value" to "${managedGroup(mutation.target.groupId)}@${mutation.target.allowedCpus.canonical}"
                )
            )
            is ThreadPlacementMutation.SetAffinity -> CapabilityCommand(
                "scheduler.thread.affinity.set",
                common + mapOf("expected" to mutation.expected.mask(), "value" to mutation.target.mask())
            )
            is ThreadPlacementMutation.SetNice -> CapabilityCommand(
                "scheduler.thread.nice.set",
                common + mapOf("expected" to mutation.expected.toString(), "value" to mutation.target.toString())
            )
            is ThreadPlacementMutation.SetScheduler -> CapabilityCommand(
                "scheduler.thread.policy.set",
                common + mapOf("expected" to mutation.expected.encoded(), "value" to mutation.target.encoded())
            )
        }
    }

    private fun managedGroup(id: String): String = "/qijing_${id.substringAfter("qijing:").take(24)}"
    private fun observedCpuSetPlacement(group: String, cpus: CpuSet): ThreadCpuSetPlacement =
        if (group.startsWith(QIJING_GROUP_PREFIX)) {
            ThreadCpuSetPlacement("qijing:${group.removePrefix(QIJING_GROUP_PREFIX)}", cpus, managedByQijing = true)
        } else {
            ThreadCpuSetPlacement(group, cpus)
        }
    private fun ThreadSchedulingState.encoded() = "${policy.name}:$priority"
    private fun CpuSet.mask(): String = cores.fold(BigInteger.ZERO) { mask, cpu -> mask.setBit(cpu) }.toString(16)
    private fun Int.toPolicy(): ThreadSchedulingPolicy? = when (this) {
        0 -> ThreadSchedulingPolicy.OTHER
        1 -> ThreadSchedulingPolicy.FIFO
        2 -> ThreadSchedulingPolicy.ROUND_ROBIN
        3 -> ThreadSchedulingPolicy.BATCH
        5 -> ThreadSchedulingPolicy.IDLE
        6 -> ThreadSchedulingPolicy.DEADLINE
        else -> null
    }

    private fun validGroup(value: String): Boolean = value.length in 1..128 && GROUP.matches(value) && ".." !in value
    private data class Observed(val pid: Int, val processStart: Long, val thread: RunningThread)
    private companion object {
        const val MAX_THREADS = 4096
        const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
        const val QIJING_GROUP_PREFIX = "/qijing_"
        val GROUP = Regex("/[A-Za-z0-9_./-]*")
    }
}
