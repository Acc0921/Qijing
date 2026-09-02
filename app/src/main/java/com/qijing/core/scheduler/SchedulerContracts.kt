package com.qijing.core.scheduler

/** Stable mode IDs shared by global profiles, scenes, and scheduler adapters. */
enum class SchedulerMode(val stableId: String) {
    POWER_SAVE("powersave"),
    BALANCED("balance"),
    PERFORMANCE("performance"),
    EXTREME("fast");

    companion object {
        fun fromStableId(value: String): SchedulerMode? = entries.firstOrNull { it.stableId == value }
    }
}

enum class SchedulerProviderId { SYSTEM, UPERF, UPERF_GT, FAS_RS, CONFIG_BRIDGE, QIJING_PROFILE }

enum class SchedulerCapability { IDENTITY_READ, STATUS_READ, MODE_PLAN }

enum class SchedulerAvailability { NOT_INSTALLED, IDENTITY_REJECTED, DETECTED, READY, DEGRADED }

data class SchedulerProbeResult(
    val provider: SchedulerProviderId,
    val availability: SchedulerAvailability,
    val version: String? = null,
    val activeMode: SchedulerMode? = null,
    val capabilities: Set<SchedulerCapability> = emptySet(),
    val detail: String
) {
    val identityVerified: Boolean
        get() = availability in setOf(SchedulerAvailability.DETECTED, SchedulerAvailability.READY, SchedulerAvailability.DEGRADED)
}

/**
 * A typed request only. It deliberately contains neither a shell fragment nor a caller-controlled
 * path; execution must be implemented later behind a separately reviewed fixed mapping.
 */
data class SchedulerModePlan(
    val provider: SchedulerProviderId,
    val mode: SchedulerMode,
    val operation: SchedulerOperation,
    val contractVersion: Int = 1
)

enum class SchedulerOperation { UPERF_MODE_SWITCH, FAS_RS_MODE_SWITCH, CONFIG_BRIDGE_MODE_SWITCH }

sealed interface SchedulerPlanResult {
    data class Planned(val plan: SchedulerModePlan) : SchedulerPlanResult
    data class Unavailable(val provider: SchedulerProviderId, val reason: String) : SchedulerPlanResult
}

interface SchedulerAdapter {
    val provider: SchedulerProviderId
    fun probe(): SchedulerProbeResult
    fun planMode(mode: SchedulerMode): SchedulerPlanResult
}
