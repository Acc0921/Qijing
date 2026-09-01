package com.qijing.core.scheduler

abstract class FixedPathSchedulerAdapter(
    final override val provider: SchedulerProviderId,
    private val reader: FixedSchedulerPathReader,
    private val modulePath: FixedSchedulerPath,
    private val expectedModuleIds: Set<String>,
    private val expectedModuleNames: Set<String>
) : SchedulerAdapter {
    protected fun verifiedIdentity(): Pair<ModuleIdentity, SchedulerProbeResult?> {
        val status = reader.status(modulePath)
        if (!status.exists) {
            return ModuleIdentity("", "", null) to SchedulerProbeResult(
                provider, SchedulerAvailability.NOT_INSTALLED, detail = "未发现固定模块标识"
            )
        }
        if (!status.exactIdentity) {
            return ModuleIdentity("", "", null) to SchedulerProbeResult(
                provider, SchedulerAvailability.IDENTITY_REJECTED, detail = "模块标识路径不是可读的规范固定路径"
            )
        }
        val identity = parseModuleIdentity(reader.readUtf8(modulePath, MODULE_PROP_LIMIT))
        if (identity == null || identity.id !in expectedModuleIds || identity.name !in expectedModuleNames) {
            return ModuleIdentity("", "", null) to SchedulerProbeResult(
                provider, SchedulerAvailability.IDENTITY_REJECTED, detail = "module.prop 身份不匹配"
            )
        }
        return identity to null
    }

    protected fun readMode(path: FixedSchedulerPath): SchedulerMode? =
        reader.readUtf8(path, MODE_LIMIT)?.trim()?.let(SchedulerMode::fromStableId)

    protected fun pathReady(path: FixedSchedulerPath): Boolean = reader.status(path).exactIdentity

    protected companion object {
        const val MODULE_PROP_LIMIT = 16 * 1024
        const val MODE_LIMIT = 32
    }
}

class UperfSchedulerAdapter(
    private val reader: FixedSchedulerPathReader = LocalFixedSchedulerPathReader()
) : FixedPathSchedulerAdapter(
    SchedulerProviderId.UPERF,
    reader,
    FixedSchedulerPath.UPERF_MODULE_PROP,
    setOf("uperf"),
    setOf("Uperf")
) {
    override fun probe(): SchedulerProbeResult {
        val (identity, failure) = verifiedIdentity()
        failure?.let { return it }
        val switchContract = pathReady(FixedSchedulerPath.UPERF_MODE_SWITCH)
        val mode = readMode(FixedSchedulerPath.UPERF_MODE_STATE)
        val capabilities = buildSet {
            add(SchedulerCapability.IDENTITY_READ)
            if (mode != null) add(SchedulerCapability.STATUS_READ)
            if (switchContract) add(SchedulerCapability.MODE_PLAN)
        }
        return SchedulerProbeResult(
            provider,
            if (switchContract) SchedulerAvailability.READY else SchedulerAvailability.DETECTED,
            identity.version,
            mode,
            capabilities,
            if (switchContract) "Uperf 固定模式契约可规划；未执行写入" else "Uperf 已识别，但固定切换契约不可用"
        )
    }

    override fun planMode(mode: SchedulerMode): SchedulerPlanResult {
        val probe = probe()
        return if (probe.identityVerified && SchedulerCapability.MODE_PLAN in probe.capabilities) {
            SchedulerPlanResult.Planned(SchedulerModePlan(provider, mode, SchedulerOperation.UPERF_MODE_SWITCH))
        } else SchedulerPlanResult.Unavailable(provider, probe.detail)
    }
}

class UperfGtSchedulerAdapter(
    private val reader: FixedSchedulerPathReader = LocalFixedSchedulerPathReader()
) : FixedPathSchedulerAdapter(
    SchedulerProviderId.UPERF_GT,
    reader,
    FixedSchedulerPath.UPERF_GT_MODULE_PROP,
    setOf("uperf"),
    setOf("Uperf Game Turbo")
) {
    override fun probe(): SchedulerProbeResult {
        val (identity, failure) = verifiedIdentity()
        failure?.let { return it }
        val switchContract = pathReady(FixedSchedulerPath.UPERF_MODE_SWITCH)
        val mode = readMode(FixedSchedulerPath.UPERF_MODE_STATE)
        return SchedulerProbeResult(
            provider,
            if (switchContract && mode != null) SchedulerAvailability.READY else if (switchContract) SchedulerAvailability.DEGRADED else SchedulerAvailability.DETECTED,
            identity.version,
            mode,
            capabilities = buildSet {
                add(SchedulerCapability.IDENTITY_READ)
                if (mode != null) add(SchedulerCapability.STATUS_READ)
                if (switchContract) add(SchedulerCapability.MODE_PLAN)
            },
            detail = if (switchContract) "UperfGT 固定 powercfg 契约可规划；未执行写入" else "UperfGT 已识别，但固定切换契约不可用"
        )
    }

    override fun planMode(mode: SchedulerMode): SchedulerPlanResult {
        val probe = probe()
        return if (probe.identityVerified && SchedulerCapability.MODE_PLAN in probe.capabilities) {
            SchedulerPlanResult.Planned(SchedulerModePlan(provider, mode, SchedulerOperation.UPERF_MODE_SWITCH))
        } else SchedulerPlanResult.Unavailable(provider, probe.detail)
    }
}

class FasRsSchedulerAdapter(
    private val reader: FixedSchedulerPathReader = LocalFixedSchedulerPathReader()
) : FixedPathSchedulerAdapter(
    SchedulerProviderId.FAS_RS,
    reader,
    FixedSchedulerPath.FAS_RS_MODULE_PROP,
    setOf("fas-rs"),
    setOf("fas-rs")
) {
    override fun probe(): SchedulerProbeResult {
        val (identity, failure) = verifiedIdentity()
        failure?.let { return it }
        val nodeReady = pathReady(FixedSchedulerPath.FAS_RS_MODE_NODE)
        val mode = if (nodeReady) readMode(FixedSchedulerPath.FAS_RS_MODE_NODE) else null
        val capabilities = buildSet {
            add(SchedulerCapability.IDENTITY_READ)
            if (mode != null) add(SchedulerCapability.STATUS_READ)
            if (nodeReady) add(SchedulerCapability.MODE_PLAN)
        }
        return SchedulerProbeResult(
            provider,
            when {
                !nodeReady -> SchedulerAvailability.DETECTED
                mode == null -> SchedulerAvailability.DEGRADED
                else -> SchedulerAvailability.READY
            },
            identity.version,
            mode,
            capabilities,
            when {
                !nodeReady -> "fas-rs 已识别，但固定 mode 节点不可用"
                mode == null -> "fas-rs mode 节点返回未知状态；仅允许类型化规划"
                else -> "fas-rs 固定 mode 节点可读取；未执行写入"
            }
        )
    }

    override fun planMode(mode: SchedulerMode): SchedulerPlanResult {
        val probe = probe()
        return if (probe.identityVerified && SchedulerCapability.MODE_PLAN in probe.capabilities) {
            SchedulerPlanResult.Planned(SchedulerModePlan(provider, mode, SchedulerOperation.FAS_RS_MODE_SWITCH))
        } else SchedulerPlanResult.Unavailable(provider, probe.detail)
    }
}
