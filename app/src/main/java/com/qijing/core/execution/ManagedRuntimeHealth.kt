package com.qijing.core.execution

internal data class ManagedRuntimeHealthProbe(
    val label: String,
    val command: CapabilityCommand,
    val expected: String
)

internal data class ManagedRuntimeHealthFailure(
    val label: String,
    val observed: String?
) {
    val detail: String
        get() = when {
            observed == null -> "$label 健康状态无法读取"
            observed.startsWith("fault|") -> "$label 已报告运行故障"
            observed.startsWith("stale|") -> "$label 进程已停止或身份不匹配"
            else -> "$label 返回了非预期状态：$observed"
        }
}

/** Builds only fixed, typed health reads for Root-owned background runtimes. */
internal object ManagedRuntimeHealthPolicy {
    fun probes(commands: List<CapabilityCommand>): List<ManagedRuntimeHealthProbe> = buildList {
        commands.forEach { command ->
            when (command.capability) {
                "scheduler.profile.gesture_boost.configure" -> {
                    val contract = ManagedGestureCommandPolicy.parse(command) ?: return@forEach
                    add(
                        ManagedRuntimeHealthProbe(
                            label = "手势调度",
                            command = command.copy(capability = "scheduler.profile.gesture_boost.health"),
                            expected = "running|${contract.contractId}"
                        )
                    )
                }

                "scheduler.profile.limiter.cluster.set" -> {
                    val cluster = ProfileLimiterCommandPolicy.parse(command) ?: return@forEach
                    if (!ManagedLimiterRuntime.isManaged(cluster) || cluster.ddrBoost) return@forEach
                    add(
                        ManagedRuntimeHealthProbe(
                            label = "policy${cluster.policy} 动态 limiter",
                            command = command.copy(capability = "scheduler.profile.limiter.health"),
                            expected = "running|${ManagedLimiterRuntime.contractId(cluster, restore = false)}"
                        )
                    )
                }
            }
        }
    }

    suspend fun firstFailure(
        commands: List<CapabilityCommand>,
        reader: suspend (CapabilityCommand) -> String?
    ): ManagedRuntimeHealthFailure? {
        probes(commands).forEach { probe ->
            val observed = reader(probe.command)?.trim()
            if (observed != probe.expected) return ManagedRuntimeHealthFailure(probe.label, observed)
        }
        return null
    }
}
