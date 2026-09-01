package com.qijing.core.scheduler

class PrivilegedSchedulerProbe(
    private val readCapability: suspend (String) -> String?
) {
    suspend fun probe(provider: SchedulerProviderId): SchedulerProbeResult {
        if (provider == SchedulerProviderId.SYSTEM) {
            return SchedulerProbeResult(
                provider,
                SchedulerAvailability.READY,
                capabilities = setOf(SchedulerCapability.IDENTITY_READ, SchedulerCapability.STATUS_READ, SchedulerCapability.MODE_PLAN),
                detail = "栖境系统调节器"
            )
        }
        val capability = when (provider) {
            SchedulerProviderId.UPERF -> "scheduler.uperf.probe"
            SchedulerProviderId.UPERF_GT -> "scheduler.uperf_gt.probe"
            SchedulerProviderId.FAS_RS -> "scheduler.fas_rs.probe"
            SchedulerProviderId.SYSTEM -> error("handled above")
        }
        val raw = readCapability(capability)
            ?: return SchedulerProbeResult(provider, SchedulerAvailability.NOT_INSTALLED, detail = "当前执行方式未读取到固定模块契约")
        val lines = raw.lineSequence().take(6).toList()
        if (lines.size != 5) return SchedulerProbeResult(provider, SchedulerAvailability.IDENTITY_REJECTED, detail = "调度器身份响应格式无效")
        val expected = when (provider) {
            SchedulerProviderId.UPERF -> "uperf" to "Uperf"
            SchedulerProviderId.UPERF_GT -> "uperf" to "Uperf Game Turbo"
            SchedulerProviderId.FAS_RS -> "fas-rs" to "fas-rs"
            SchedulerProviderId.SYSTEM -> error("handled above")
        }
        if (lines[0] != expected.first || lines[1] != expected.second) {
            return SchedulerProbeResult(provider, SchedulerAvailability.IDENTITY_REJECTED, detail = "module.prop 身份不匹配")
        }
        val mode = SchedulerMode.fromStableId(lines[3])
        val planReady = lines[4] == "1"
        val capabilities = buildSet {
            add(SchedulerCapability.IDENTITY_READ)
            if (mode != null) add(SchedulerCapability.STATUS_READ)
            if (planReady) add(SchedulerCapability.MODE_PLAN)
        }
        return SchedulerProbeResult(
            provider = provider,
            availability = when {
                planReady && mode != null -> SchedulerAvailability.READY
                planReady -> SchedulerAvailability.DEGRADED
                else -> SchedulerAvailability.DETECTED
            },
            version = lines[2].ifBlank { null },
            activeMode = mode,
            capabilities = capabilities,
            detail = when {
                planReady -> "固定模式契约可用"
                else -> "已识别，但模式契约不可用"
            }
        )
    }
}
