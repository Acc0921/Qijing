package com.qijing.core.scheduler.profile

/** The four stable modes understood by Scene-compatible profiles. */
enum class ProfileMode(val stableId: String) {
    POWER_SAVE("powersave"),
    BALANCED("balance"),
    PERFORMANCE("performance"),
    FAST("fast")
}

enum class ProfileWorkload(val stableId: String) {
    APP("app"),
    GAME("game"),
    CALL("call")
}

enum class ProfilePhase { ACTIVE, INACTIVE }

data class ProfileRoute(
    val mode: ProfileMode,
    val workload: ProfileWorkload,
    val phase: ProfilePhase
)

/** A normalized kernel endpoint. This is data, never an executable command fragment. */
class KernelNode internal constructor(val absolutePath: String) {
    override fun equals(other: Any?): Boolean = other is KernelNode && absolutePath == other.absolutePath
    override fun hashCode(): Int = absolutePath.hashCode()
    override fun toString(): String = "KernelNode($absolutePath)"
}

enum class ValueNotation { PLAIN, HASH_PREFIXED, CARET_PREFIXED }

enum class CpuFrequencyBound { MINIMUM, MAXIMUM }

/**
 * Profile values remain opaque data. Prefixes used by legacy profiles are retained explicitly so
 * a later, device-specific planner can decide whether it supports their semantics.
 */
data class ProfileValue(
    val text: String,
    val notation: ValueNotation = ValueNotation.PLAIN
)

sealed interface ProfileFeatureValue {
    data class ObjectValue(val entries: Map<String, ProfileFeatureValue>) : ProfileFeatureValue
    data class ArrayValue(val entries: List<ProfileFeatureValue>) : ProfileFeatureValue
    data class StringValue(val value: String) : ProfileFeatureValue
    data class NumberValue(val value: String) : ProfileFeatureValue
    data class BooleanValue(val value: Boolean) : ProfileFeatureValue
    data object NullValue : ProfileFeatureValue
}

data class OperationOrigin(
    val section: String,
    val presetChain: List<String> = emptyList(),
    val index: Int
)

/** Pure, typed profile intent. No variant is allowed to contain shell. */
sealed interface ProfileOperation {
    val origin: OperationOrigin

    data class Capture(
        val target: KernelNode,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class Write(
        val target: KernelNode,
        val value: ProfileValue,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class Values(
        val sourcePreset: String,
        val targets: List<KernelNode>,
        val values: List<ProfileValue>,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class CpuSet(
        val background: ProfileValue,
        val systemBackground: ProfileValue,
        val foreground: ProfileValue,
        val topApp: ProfileValue,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class CpuFrequenciesMin(
        val policies: List<ProfileValue>,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class CpuFrequenciesMax(
        val policies: List<ProfileValue>,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class CpuFrequency(
        val policy: String,
        val bound: CpuFrequencyBound,
        val value: ProfileValue,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class TargetLoads(
        val policies: List<ProfileValue>,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class HispeedFrequencies(
        val policies: List<ProfileValue>,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class Governor(
        val name: String,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class Limiter(
        val profileName: String,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class FrameRate(
        val value: ProfileValue,
        override val origin: OperationOrigin
    ) : ProfileOperation

    data class PlatformReset(override val origin: OperationOrigin) : ProfileOperation
}

data class CompiledProfileProgram(
    val aliases: Map<String, KernelNode>,
    val features: Map<String, ProfileFeatureValue>,
    val reset: List<ProfileOperation>,
    /** Contains all 4 x 3 x 2 routes; an unsupported active category is represented by an empty list. */
    val routes: Map<ProfileRoute, List<ProfileOperation>>,
    val applicationRules: List<ImportedApplicationRule>
)

data class ImportedModeProgram(
    val call: List<ProfileOperation>,
    val active: List<ProfileOperation>,
    val inactive: List<ProfileOperation>,
    /** Known non-executable extension data such as fas is retained for a future typed planner. */
    val metadata: Map<String, ProfileFeatureValue> = emptyMap()
)

data class ImportedApplicationRule(
    val friendlyName: String,
    val packageSelectors: Set<String>,
    val workload: ProfileWorkload,
    val modes: Map<ProfileMode, ImportedModeProgram>,
    val importName: String
)

sealed interface ProfileCompileResult {
    data class Compiled(val program: CompiledProfileProgram) : ProfileCompileResult
    data class Rejected(val reason: String) : ProfileCompileResult
}
