package com.qijing.core.scheduler.pack

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class SchedulerPackLimits(
    val maxArchiveBytes: Long = 16L * 1024 * 1024,
    val maxExpandedBytes: Long = 32L * 1024 * 1024,
    val maxEntryBytes: Int = 2 * 1024 * 1024,
    val maxEntries: Int = 2_048,
    val maxPathCharacters: Int = 512
) {
    init {
        require(maxArchiveBytes > 0)
        require(maxExpandedBytes > 0)
        require(maxEntryBytes > 0)
        require(maxEntries > 0)
        require(maxPathCharacters > 0)
    }
}

data class SchedulerModuleMetadata(
    val id: String,
    val name: String,
    val version: String?,
    val versionCode: Long?,
    val author: String?,
    val description: String?
)

data class CpuTopology(
    val clusterCoreCounts: List<Int>
) {
    val totalCoreCount: Int = clusterCoreCounts.sum()
    val canonical: String = clusterCoreCounts.joinToString("+")
    val expectedCpuCores: Set<Int> = (0 until totalCoreCount).toSet()

    companion object {
        fun parse(raw: String): CpuTopology? {
            val normalized = raw.trim()
            if (!TOPOLOGY.matches(normalized)) return null
            val groups = normalized.split('+').map { it.toIntOrNull() ?: return null }
            if (groups.any { it <= 0 } || groups.sum() > 256) return null
            return CpuTopology(groups)
        }

        private val TOPOLOGY = Regex("[1-9][0-9]{0,2}(?:\\+[1-9][0-9]{0,2}){0,7}")
    }
}

data class SchedulerPackHardwareRequirement(
    val socIdentifiers: Set<String>,
    val socModels: Set<String>,
    val platforms: Set<String>,
    val topology: CpuTopology,
    val requiredCpuCores: Set<Int> = topology.expectedCpuCores
)

data class SchedulerVariantManifest(
    val version: String?,
    val versionCode: Long?,
    val author: String?,
    val projectUrl: String?,
    val features: Map<String, Boolean>
)

/**
 * The recognized configuration documents are retained in memory only. Import never extracts an
 * archive or treats scripts and unknown files as executable input.
 */
data class SchedulerPackVariant(
    val id: String,
    val relativePath: String,
    val categoryPath: List<String>,
    val hardware: SchedulerPackHardwareRequirement,
    val manifest: SchedulerVariantManifest,
    val manifestJson: String,
    val profileJson: String,
    val threadsJson: String,
    val imports: Map<String, String> = emptyMap(),
    val description: String?
) {
    fun compatibilityWith(device: SchedulerPackDevice): SchedulerPackCompatibility =
        SchedulerPackCompatibilityEvaluator.evaluate(hardware, device)
}

data class SchedulerPack(
    val id: String,
    val module: SchedulerModuleMetadata,
    val contentFingerprintSha256: String,
    val variants: List<SchedulerPackVariant>
)

data class SchedulerPackDevice(
    val socModel: String,
    val platform: String?,
    val cpuCores: Set<Int>,
    val clusterCoreSets: List<Set<Int>> = emptyList(),
    val socIdentifiers: Set<String> = emptySet()
)

enum class SchedulerPackMismatch {
    INVALID_DEVICE_CPU_SET,
    SOC_NOT_SUPPORTED,
    PLATFORM_NOT_SUPPORTED,
    CPU_CORE_SET_MISMATCH,
    CPU_TOPOLOGY_MISMATCH
}

data class SchedulerPackCompatibility(
    val compatible: Boolean,
    val mismatches: Set<SchedulerPackMismatch>
)

object SchedulerPackCompatibilityEvaluator {
    fun evaluate(
        requirement: SchedulerPackHardwareRequirement,
        device: SchedulerPackDevice
    ): SchedulerPackCompatibility {
        val mismatches = linkedSetOf<SchedulerPackMismatch>()
        val deviceCoresValid = device.cpuCores.isNotEmpty() &&
            device.cpuCores.all { it in 0..255 } &&
            device.clusterCoreSets.all { it.isNotEmpty() && it.all(device.cpuCores::contains) } &&
            device.clusterCoreSets.flatten().toSet().size == device.clusterCoreSets.sumOf(Set<Int>::size)
        if (!deviceCoresValid) mismatches += SchedulerPackMismatch.INVALID_DEVICE_CPU_SET

        val deviceSocNames = (device.socIdentifiers + device.socModel)
            .map(::canonicalHardwareName)
            .filter(String::isNotEmpty)
            .toSet()
        val acceptedSocNames = (requirement.socIdentifiers + requirement.socModels)
            .map(::canonicalHardwareName)
            .toSet()
        if (deviceSocNames.intersect(acceptedSocNames).isEmpty()) {
            mismatches += SchedulerPackMismatch.SOC_NOT_SUPPORTED
        }

        if (requirement.platforms.isNotEmpty()) {
            val platform = device.platform?.let(::canonicalHardwareName)
            if (platform == null || requirement.platforms.map(::canonicalHardwareName).none { it == platform }) {
                mismatches += SchedulerPackMismatch.PLATFORM_NOT_SUPPORTED
            }
        }

        if (device.cpuCores != requirement.requiredCpuCores) {
            mismatches += SchedulerPackMismatch.CPU_CORE_SET_MISMATCH
        }

        if (device.clusterCoreSets.isEmpty()) {
            mismatches += SchedulerPackMismatch.CPU_TOPOLOGY_MISMATCH
        } else {
            val actualCounts = device.clusterCoreSets
                .sortedBy { it.minOrNull() }
                .map(Set<Int>::size)
            if (actualCounts != requirement.topology.clusterCoreCounts) {
                mismatches += SchedulerPackMismatch.CPU_TOPOLOGY_MISMATCH
            }
        }

        return SchedulerPackCompatibility(mismatches.isEmpty(), mismatches)
    }
}

sealed interface SchedulerPackImportResult {
    data class Imported(val pack: SchedulerPack) : SchedulerPackImportResult
    data class Rejected(val reason: SchedulerPackRejectReason, val detail: String) : SchedulerPackImportResult
}

enum class SchedulerPackRejectReason {
    INVALID_ZIP,
    ARCHIVE_TOO_LARGE,
    EXPANDED_CONTENT_TOO_LARGE,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    UNSAFE_ENTRY_PATH,
    DUPLICATE_ENTRY,
    INVALID_TEXT,
    MISSING_MODULE_METADATA,
    INVALID_MODULE_METADATA,
    MISSING_VARIANTS,
    INCOMPLETE_VARIANT,
    INVALID_VARIANT_METADATA,
    INVALID_CONFIGURATION_JSON
}

internal fun canonicalIdentity(raw: String): String = Normalizer
    .normalize(raw.trim(), Normalizer.Form.NFC)
    .lowercase(Locale.ROOT)

internal fun canonicalHardwareName(raw: String): String = canonicalIdentity(raw)
    .replace("_", "")
    .replace("-", "")
    .replace(" ", "")

internal fun stableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.ROOT, it) }
