package com.qijing.core.scheduler

import org.json.JSONArray
import org.json.JSONObject

class CpuSet private constructor(val cores: Set<Int>) {
    val canonical: String = cores.sorted().joinToString(",")

    /** CPU masks are values; independently observed masks with the same cores must compare equally. */
    override fun equals(other: Any?): Boolean = other is CpuSet && cores == other.cores
    override fun hashCode(): Int = cores.hashCode()
    override fun toString(): String = canonical

    companion object {
        fun parse(raw: String, availableCores: Set<Int>): CpuSet? {
            if (raw.isBlank() || raw.length > 128 || availableCores.isEmpty()) return null
            val parsed = linkedSetOf<Int>()
            raw.split(',').forEach { segment ->
                val part = segment.trim()
                if (part.isEmpty()) return null
                val range = part.split('-')
                when (range.size) {
                    1 -> range[0].toIntOrNull()?.let(parsed::add) ?: return null
                    2 -> {
                        val start = range[0].toIntOrNull() ?: return null
                        val end = range[1].toIntOrNull() ?: return null
                        if (start > end || end - start > 255) return null
                        parsed.addAll(start..end)
                    }
                    else -> return null
                }
            }
            if (parsed.isEmpty() || parsed.any { it !in availableCores }) return null
            return CpuSet(parsed)
        }
    }
}

class ThreadNamePattern private constructor(
    val value: String,
    val prefix: Boolean
) {
    fun matches(threadName: String): Boolean = if (prefix) threadName.startsWith(value) else threadName == value

    override fun equals(other: Any?): Boolean = other is ThreadNamePattern && value == other.value && prefix == other.prefix
    override fun hashCode(): Int = 31 * value.hashCode() + prefix.hashCode()

    companion object {
        fun parse(raw: String): ThreadNamePattern? {
            val normalized = raw.trim()
            if (normalized.isEmpty() || normalized.length > 64 || '\u0000' in normalized) return null
            if ('*' !in normalized) return ThreadNamePattern(normalized, prefix = false)
            if (!normalized.endsWith('*') || normalized.count { it == '*' } != 1) return null
            return normalized.dropLast(1).takeIf(String::isNotEmpty)?.let { ThreadNamePattern(it, prefix = true) }
        }
    }
}

private fun ThreadNamePattern.overlaps(other: ThreadNamePattern): Boolean = when {
    !prefix && !other.prefix -> value == other.value
    prefix && other.prefix -> value.startsWith(other.value) || other.value.startsWith(value)
    prefix -> other.value.startsWith(value)
    else -> value.startsWith(other.value)
}

data class ThreadPlacementRule(
    val matcher: ThreadNamePattern,
    val cpuSet: CpuSet,
    val source: ThreadPlacementSource = ThreadPlacementSource.COMM
)

enum class ThreadPlacementSource {
    MAIN_THREAD,
    UNITY_MAIN,
    COMM,
    HEAVY_THREAD,
    TRASHY,
    FALLBACK
}

data class AppThreadPlacementProfile(
    val label: String,
    val packageNames: Set<String>,
    val rules: List<ThreadPlacementRule>,
    val fallback: CpuSet,
    val roundRobinMatchers: List<ThreadNamePattern> = emptyList(),
    val niceMatchers: List<ThreadNamePattern> = emptyList(),
    val mainThreadCpuSet: CpuSet? = null,
    val trashyMatchers: List<ThreadNamePattern> = emptyList(),
    val trashyCpuSet: CpuSet = fallback
)

data class ThreadPlacementDecision(
    val profileLabel: String,
    val cpuSet: CpuSet,
    val matchedPattern: ThreadNamePattern?,
    val requestRoundRobin: Boolean,
    val requestNiceAdjustment: Boolean,
    val placementSource: ThreadPlacementSource = if (matchedPattern == null) {
        ThreadPlacementSource.FALLBACK
    } else {
        ThreadPlacementSource.COMM
    },
    val trashyPattern: ThreadNamePattern? = null,
    val requestTrashyDemotion: Boolean = trashyPattern != null
)

sealed interface ThreadPlacementLoad {
    data class Loaded(val ruleSet: ThreadPlacementRuleSet) : ThreadPlacementLoad
    data class Rejected(val reason: String) : ThreadPlacementLoad
}

class ThreadPlacementRuleSet private constructor(
    val profiles: List<AppThreadPlacementProfile>
) {
    fun decide(
        packageName: String,
        threadName: String,
        isProcessMainThread: Boolean = false
    ): ThreadPlacementDecision? {
        val profile = profiles.firstOrNull { packageName in it.packageNames } ?: return null
        val explicit = profile.rules.firstOrNull { it.matcher.matches(threadName) }
        val trashy = profile.trashyMatchers.firstOrNull { it.matches(threadName) }
        val mainThreadCpuSet = profile.mainThreadCpuSet.takeIf { isProcessMainThread }
        val cpuSet = mainThreadCpuSet ?: explicit?.cpuSet ?: profile.trashyCpuSet.takeIf { trashy != null } ?: profile.fallback
        val source = when {
            mainThreadCpuSet != null -> ThreadPlacementSource.MAIN_THREAD
            explicit != null -> explicit.source
            trashy != null -> ThreadPlacementSource.TRASHY
            else -> ThreadPlacementSource.FALLBACK
        }
        return ThreadPlacementDecision(
            profileLabel = profile.label,
            cpuSet = cpuSet,
            matchedPattern = explicit?.matcher ?: trashy,
            requestRoundRobin = profile.roundRobinMatchers.any { it.matches(threadName) },
            requestNiceAdjustment = profile.niceMatchers.any { it.matches(threadName) },
            placementSource = source,
            trashyPattern = trashy,
            requestTrashyDemotion = trashy != null
        )
    }

    companion object {
        fun create(profiles: List<AppThreadPlacementProfile>): ThreadPlacementLoad {
            if (profiles.isEmpty()) return ThreadPlacementLoad.Rejected("线程配置为空")
            if (profiles.size > MAX_PROFILES) return ThreadPlacementLoad.Rejected("线程配置数量超过上限")
            val packages = linkedSetOf<String>()
            profiles.forEach { profile ->
                if (profile.label.isBlank() || profile.label.length > 64) return ThreadPlacementLoad.Rejected("线程配置名称无效")
                if (profile.packageNames.isEmpty() || profile.packageNames.size > MAX_PACKAGES_PER_PROFILE) {
                    return ThreadPlacementLoad.Rejected("${profile.label} 的应用数量无效")
                }
                if (profile.rules.size > MAX_RULES_PER_PROFILE) return ThreadPlacementLoad.Rejected("${profile.label} 的线程规则过多")
                if (profile.trashyMatchers.size > MAX_RULES_PER_PROFILE) return ThreadPlacementLoad.Rejected("${profile.label} 的低优先级线程规则过多")
                if (profile.packageNames.any { !PACKAGE_NAME.matches(it) }) return ThreadPlacementLoad.Rejected("${profile.label} 包含无效包名")
                val duplicate = profile.packageNames.firstOrNull { !packages.add(it) }
                if (duplicate != null) return ThreadPlacementLoad.Rejected("包名 $duplicate 同时属于多个线程配置")
                val duplicateMatcher = profile.rules.groupBy { it.matcher }.entries.firstOrNull { it.value.size > 1 }
                if (duplicateMatcher != null) return ThreadPlacementLoad.Rejected("${profile.label} 包含重复线程匹配规则")
                if (profile.trashyMatchers.distinct().size != profile.trashyMatchers.size) {
                    return ThreadPlacementLoad.Rejected("${profile.label} 包含重复低优先级线程规则")
                }
                if (profile.trashyMatchers.any { demoted ->
                        profile.roundRobinMatchers.any { demoted.overlaps(it) } ||
                            profile.niceMatchers.any { demoted.overlaps(it) }
                    }
                ) {
                    return ThreadPlacementLoad.Rejected("${profile.label} 的低优先级线程同时请求了提权")
                }
                val unityMainTargets = profile.rules
                    .filter { it.source == ThreadPlacementSource.UNITY_MAIN }
                    .map { it.cpuSet }
                    .distinct()
                if (profile.mainThreadCpuSet != null && unityMainTargets.any { it != profile.mainThreadCpuSet }) {
                    return ThreadPlacementLoad.Rejected("${profile.label} 的 main_thread 与 unity_main CPU 归属冲突")
                }
            }
            return ThreadPlacementLoad.Loaded(ThreadPlacementRuleSet(profiles))
        }

        private const val MAX_PROFILES = 512
        private const val MAX_PACKAGES_PER_PROFILE = 32
        private const val MAX_RULES_PER_PROFILE = 256
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

/** Parses only the bounded thread-placement subset; unknown fields never become shell or paths. */
class ThreadPlacementJsonParser {
    fun parse(raw: String, availableCores: Set<Int>): ThreadPlacementLoad {
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            return ThreadPlacementLoad.Rejected("线程配置为空或超过大小上限")
        }
        if (availableCores.isEmpty() || availableCores.any { it !in 0..255 }) {
            return ThreadPlacementLoad.Rejected("设备 CPU 集合无效")
        }
        return runCatching {
            val array = JSONArray(raw)
            if (array.length() !in 1..512) return ThreadPlacementLoad.Rejected("线程配置数量无效")
            val profiles = (0 until array.length()).map { index ->
                parseProfile(array.getJSONObject(index), availableCores)
                    ?: return ThreadPlacementLoad.Rejected("第 ${index + 1} 项线程配置无效")
            }
            ThreadPlacementRuleSet.create(profiles)
        }.getOrElse { ThreadPlacementLoad.Rejected("线程配置 JSON 无效") }
    }

    private fun parseProfile(root: JSONObject, availableCores: Set<Int>): AppThreadPlacementProfile? {
        val label = (root.opt("friendly") as? String)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        val packages = root.optJSONArray("packages")?.toStringSet(MAX_PACKAGES_PER_PROFILE) ?: return null
        val placement = root.optJSONObject("cpuset") ?: return null
        val fallback = CpuSet.parse(placement.opt("other") as? String ?: return null, availableCores) ?: return null
        val mainThreadCpuSet = optionalCpuSet(placement, "main_thread", availableCores) ?: return null
        val unityMainCpuSet = optionalCpuSet(placement, "unity_main", availableCores) ?: return null
        if (mainThreadCpuSet.value != null && unityMainCpuSet.value != null && mainThreadCpuSet.value != unityMainCpuSet.value) {
            return null
        }
        val rules = mutableListOf<ThreadPlacementRule>()
        val comm = placement.optJSONObject("comm")
        if (comm != null) {
            val keys = comm.keys().asSequence().toList()
            if (keys.size > MAX_CPUSET_GROUPS) return null
            keys.forEach { cpuSetRaw ->
                val cpuSet = CpuSet.parse(cpuSetRaw, availableCores) ?: return null
                val matchers = comm.optJSONArray(cpuSetRaw)?.toPatterns(MAX_PATTERNS_PER_GROUP) ?: return null
                matchers.forEach { rules += ThreadPlacementRule(it, cpuSet, ThreadPlacementSource.COMM) }
            }
        }
        val heavyCores = (placement.opt("heavy_cores") as? String)?.let { CpuSet.parse(it, availableCores) }
        val heavyNames = (placement.opt("heavy_thread") as? String)?.split(';')
            ?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        if (heavyNames.isNotEmpty() && heavyCores == null) return null
        heavyNames.forEach { name ->
            val matcher = ThreadNamePattern.parse(if (name.endsWith('*')) name else "$name*") ?: return null
            rules += ThreadPlacementRule(matcher, heavyCores!!, ThreadPlacementSource.HEAVY_THREAD)
        }
        unityMainCpuSet.value?.let { cpuSet ->
            rules += ThreadPlacementRule(
                ThreadNamePattern.parse("UnityMain*")!!,
                cpuSet,
                ThreadPlacementSource.UNITY_MAIN
            )
        }
        val conflicts = rules.groupBy { it.matcher }.values.any { matches ->
            matches.map { it.cpuSet }.distinct().size > 1
        }
        if (conflicts) return null
        val roundRobin = if (placement.has("rr")) {
            placement.optJSONArray("rr")?.toPatterns(MAX_FLAG_PATTERNS) ?: return null
        } else emptyList()
        val nice = if (placement.has("ni")) {
            placement.optJSONArray("ni")?.toPatterns(MAX_FLAG_PATTERNS) ?: return null
        } else emptyList()
        val trashy = if (placement.has("trashy")) {
            placement.optJSONArray("trashy")?.toPatterns(MAX_FLAG_PATTERNS) ?: return null
        } else emptyList()
        if (trashy.any { demoted -> roundRobin.any { demoted.overlaps(it) } || nice.any { demoted.overlaps(it) } }) {
            return null
        }
        return AppThreadPlacementProfile(
            label = label,
            packageNames = packages,
            rules = rules.distinctBy { it.matcher }.sortedWith(
                compareBy<ThreadPlacementRule> { it.matcher.prefix }
                    .thenByDescending { it.matcher.value.length }
            ),
            fallback = fallback,
            roundRobinMatchers = roundRobin,
            niceMatchers = nice,
            mainThreadCpuSet = mainThreadCpuSet.value,
            trashyMatchers = trashy,
            trashyCpuSet = fallback
        )
    }

    /** Distinguishes an absent optional key from a present but invalid value. */
    private fun optionalCpuSet(root: JSONObject, key: String, availableCores: Set<Int>): OptionalCpuSet? {
        if (!root.has(key)) return OptionalCpuSet(null)
        val raw = root.opt(key) as? String ?: return null
        return CpuSet.parse(raw, availableCores)?.let(::OptionalCpuSet)
    }

    private fun JSONArray.toStringSet(limit: Int): Set<String>? {
        if (length() !in 1..limit) return null
        val values = linkedSetOf<String>()
        for (index in 0 until length()) {
            val value = (opt(index) as? String)?.trim() ?: return null
            if (value.isEmpty() || !values.add(value)) return null
        }
        return values
    }

    private fun JSONArray?.toPatterns(limit: Int): List<ThreadNamePattern>? {
        if (this == null || length() !in 1..limit) return null
        val values = mutableListOf<ThreadNamePattern>()
        for (index in 0 until length()) {
            values += ThreadNamePattern.parse(opt(index) as? String ?: return null) ?: return null
        }
        return values.distinct().takeIf { it.size == values.size }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 512 * 1024
        const val MAX_PACKAGES_PER_PROFILE = 32
        const val MAX_CPUSET_GROUPS = 32
        const val MAX_PATTERNS_PER_GROUP = 128
        const val MAX_FLAG_PATTERNS = 128
    }

    private data class OptionalCpuSet(val value: CpuSet?)
}
