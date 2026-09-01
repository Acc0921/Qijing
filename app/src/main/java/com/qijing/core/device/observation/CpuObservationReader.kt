package com.qijing.core.device.observation

class CpuObservationReader(
    private val fileSystem: ObservationFileSystem = LocalObservationFileSystem(),
    private val cpuRoot: String = "/sys/devices/system/cpu",
    private val procStatPath: String = "/proc/stat",
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var previousTicks: Map<Int, CpuTicks> = emptyMap()

    @Synchronized
    fun read(): CpuObservation {
        val sampledAt = clock()
        val policyDirectory = fileSystem.list("$cpuRoot/cpufreq")
        val policyNames = (policyDirectory as? DirectoryReadResult.Success)?.names.orEmpty()
            .filter { it.matches(POLICY_NAME) }
            .sortedBy { it.removePrefix("policy").toIntOrNull() }
        val policies = policyNames.map { readPolicy(it, sampledAt) }
        val policyCount = when {
            policyNames.isNotEmpty() -> ObservedMetric.available(policyNames.size, MetricSource.SYSFS, sampledAt)
            policyDirectory is DirectoryReadResult.PermissionDenied -> ObservedMetric.unavailable(MetricStatus.PERMISSION_DENIED, sampledAt, MetricSource.SYSFS, "当前身份无权枚举 CPU policy")
            policyDirectory is DirectoryReadResult.Failed -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, policyDirectory.detail)
            else -> ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.SYSFS, "内核未公开 CPUFreq policy")
        }
        val policyByCore = buildMap<Int, CpuPolicyObservation> {
            policies.forEach { policy -> policy.relatedCores.forEach { put(it, policy) } }
        }

        val coreIds = discoverCoreIds(policies)
        val ticksResult = readCpuTicks()
        val ticks = (ticksResult as? CpuTicksRead.Success)?.values.orEmpty()
        val cores = coreIds.map { coreId ->
            val policy = policyByCore[coreId]
            CpuCoreObservation(
                id = coreId,
                online = readOnline(coreId, sampledAt),
                policyId = policy?.id,
                currentFrequencyKHz = readCoreFrequency(coreId, policy, sampledAt),
                loadPercent = calculateLoad(coreId, ticksResult, ticks[coreId], sampledAt)
            )
        }
        if (ticksResult is CpuTicksRead.Success) previousTicks = ticks
        return CpuObservation(policyCount, policies, cores, sampledAt)
    }

    private fun discoverCoreIds(policies: List<CpuPolicyObservation>): List<Int> {
        val directoryIds = (fileSystem.list(cpuRoot) as? DirectoryReadResult.Success)
            ?.names.orEmpty()
            .mapNotNull { CPU_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        return (directoryIds + policies.flatMap { it.relatedCores }).distinct().sorted()
    }

    private fun readPolicy(name: String, sampledAt: Long): CpuPolicyObservation {
        val path = "$cpuRoot/cpufreq/$name"
        val related = readCpuList("$path/related_cpus")
            .ifEmpty { readCpuList("$path/affected_cpus") }
        return CpuPolicyObservation(
            id = name,
            relatedCores = related,
            currentFrequencyKHz = readLongMetric("$path/scaling_cur_freq", sampledAt),
            hardwareMinFrequencyKHz = readLongMetric("$path/cpuinfo_min_freq", sampledAt),
            hardwareMaxFrequencyKHz = readLongMetric("$path/cpuinfo_max_freq", sampledAt),
            scalingMinFrequencyKHz = readLongMetric("$path/scaling_min_freq", sampledAt),
            scalingMaxFrequencyKHz = readLongMetric("$path/scaling_max_freq", sampledAt),
            governor = readStringMetric("$path/scaling_governor", sampledAt),
            availableGovernors = readStringSetMetric("$path/scaling_available_governors", sampledAt)
        )
    }

    private fun readOnline(coreId: Int, sampledAt: Long): ObservedMetric<Boolean> {
        val global = fileSystem.readText("$cpuRoot/online")
        if (global is FileReadResult.Success) {
            val parsed = parseCpuList(global.text)
            if (parsed != null) {
                return ObservedMetric.available(coreId in parsed, MetricSource.SYSFS, sampledAt)
            }
            return ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "online 核心列表格式无效")
        }
        val perCore = fileSystem.readText("$cpuRoot/cpu$coreId/online")
        if (perCore is FileReadResult.Success) {
            return when (perCore.text.trim()) {
                "0" -> ObservedMetric.available(false, MetricSource.SYSFS, sampledAt)
                "1" -> ObservedMetric.available(true, MetricSource.SYSFS, sampledAt)
                else -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "online 节点格式无效")
            }
        }
        // cpu0 commonly omits its online node because it cannot be offlined.
        if (coreId == 0 && global is FileReadResult.Missing && perCore is FileReadResult.Missing) {
            return ObservedMetric.available(true, MetricSource.SYSFS, sampledAt, "cpu0 无 online 节点，按内核约定视为在线")
        }
        val failure = if (perCore !is FileReadResult.Missing) perCore else global
        return ObservedMetric.unavailable(failure.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, failure.detailWhenUnavailable())
    }

    private fun readCoreFrequency(
        coreId: Int,
        policy: CpuPolicyObservation?,
        sampledAt: Long
    ): ObservedMetric<Long> {
        val exact = firstLongMetric(
            listOf(
                "$cpuRoot/cpu$coreId/cpufreq/cpuinfo_cur_freq",
                "$cpuRoot/cpu$coreId/cpufreq/scaling_cur_freq"
            ),
            sampledAt
        )
        if (exact.status == MetricStatus.AVAILABLE || policy == null) return exact
        val policyValue = policy.currentFrequencyKHz
        return if (policyValue.status == MetricStatus.AVAILABLE) {
            policyValue.copy(detail = "来自 ${policy.id} 策略域；不是独立每核硬件计数")
        } else exact
    }

    private fun calculateLoad(
        coreId: Int,
        ticksResult: CpuTicksRead,
        current: CpuTicks?,
        sampledAt: Long
    ): ObservedMetric<Double> {
        if (ticksResult !is CpuTicksRead.Success) {
            return ObservedMetric.unavailable(ticksResult.status, sampledAt, MetricSource.PROCFS, ticksResult.detail)
        }
        if (current == null) {
            return ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.PROCFS, "未发现该核心的 /proc/stat 计数")
        }
        val previous = previousTicks[coreId]
            ?: return ObservedMetric.unavailable(MetricStatus.SAMPLING, sampledAt, MetricSource.PROCFS, "需要第二次采样计算负载")
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L || idleDelta > totalDelta) {
            return ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.PROCFS, "CPU 计数器重置或倒退")
        }
        val percent = ((totalDelta - idleDelta) * 100.0 / totalDelta).coerceIn(0.0, 100.0)
        return ObservedMetric.available(percent, MetricSource.DERIVED, sampledAt, "由两次 /proc/stat 采样差值计算", estimated = true)
    }

    private fun readCpuTicks(): CpuTicksRead = when (val result = fileSystem.readText(procStatPath)) {
        is FileReadResult.Success -> {
            val values = result.text.lineSequence().mapNotNull(::parseCpuTicks).toMap()
            if (values.isEmpty()) CpuTicksRead.Failure(MetricStatus.INVALID, "未解析到每核 CPU 计数")
            else CpuTicksRead.Success(values)
        }
        else -> CpuTicksRead.Failure(result.statusWhenUnavailable(), result.detailWhenUnavailable())
    }

    private fun parseCpuTicks(line: String): Pair<Int, CpuTicks>? {
        val fields = line.trim().split(WHITESPACE)
        val id = CPU_STAT.matchEntire(fields.firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val numbers = fields.drop(1).map { it.toLongOrNull() ?: return null }
        if (numbers.size < 4) return null
        val user = numbers.getOrElse(0) { 0 }
        val nice = numbers.getOrElse(1) { 0 }
        val system = numbers.getOrElse(2) { 0 }
        val idle = numbers.getOrElse(3) { 0 }
        val ioWait = numbers.getOrElse(4) { 0 }
        val irq = numbers.getOrElse(5) { 0 }
        val softIrq = numbers.getOrElse(6) { 0 }
        val steal = numbers.getOrElse(7) { 0 }
        return id to CpuTicks(user + nice + system + idle + ioWait + irq + softIrq + steal, idle + ioWait)
    }

    private fun readCpuList(path: String): Set<Int> =
        (fileSystem.readText(path) as? FileReadResult.Success)?.text?.let(::parseCpuList).orEmpty()

    private fun parseCpuList(text: String): Set<Int>? {
        val result = linkedSetOf<Int>()
        val tokens = text.trim().split(Regex("[,\\s]+"))
        if (tokens.all(String::isBlank)) return emptySet()
        for (token in tokens.filter(String::isNotBlank)) {
            val range = token.split('-')
            when (range.size) {
                1 -> result += range[0].toIntOrNull() ?: return null
                2 -> {
                    val start = range[0].toIntOrNull() ?: return null
                    val end = range[1].toIntOrNull() ?: return null
                    if (start > end) return null
                    result += start..end
                }
                else -> return null
            }
        }
        return result
    }

    private fun firstLongMetric(paths: List<String>, sampledAt: Long): ObservedMetric<Long> {
        var failure: ObservedMetric<Long>? = null
        paths.forEach { path ->
            val metric = readLongMetric(path, sampledAt)
            if (metric.status == MetricStatus.AVAILABLE) return metric
            if (metric.status != MetricStatus.UNSUPPORTED) failure = failure ?: metric
        }
        return failure ?: ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.SYSFS, "频率节点不存在")
    }

    private fun readLongMetric(path: String, sampledAt: Long): ObservedMetric<Long> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> when (val value = result.text.trim().toLongOrNull()) {
                null -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "数值格式无效")
                0L -> ObservedMetric.unavailable(MetricStatus.INACTIVE, sampledAt, MetricSource.SYSFS, "频率节点返回 0，核心可能离线或未活动")
                in Long.MIN_VALUE until 0L -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "频率不能为负值")
                else -> ObservedMetric.available(value, MetricSource.SYSFS, sampledAt)
            }
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun readStringMetric(path: String, sampledAt: Long): ObservedMetric<String> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> result.text.trim().takeIf(String::isNotEmpty)?.let {
                ObservedMetric.available(it, MetricSource.SYSFS, sampledAt)
            } ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "文本为空")
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun readStringSetMetric(path: String, sampledAt: Long): ObservedMetric<Set<String>> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> {
                val values = result.text.trim().split(WHITESPACE).filter(String::isNotBlank).toSet()
                if (values.isEmpty()) ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "列表为空")
                else ObservedMetric.available(values, MetricSource.SYSFS, sampledAt)
            }
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private data class CpuTicks(val total: Long, val idle: Long)

    private sealed interface CpuTicksRead {
        val status: MetricStatus
        val detail: String?

        data class Success(val values: Map<Int, CpuTicks>) : CpuTicksRead {
            override val status = MetricStatus.AVAILABLE
            override val detail: String? = null
        }

        data class Failure(override val status: MetricStatus, override val detail: String?) : CpuTicksRead
    }

    private companion object {
        val CPU_NAME = Regex("cpu(\\d+)")
        val CPU_STAT = Regex("cpu(\\d+)")
        val POLICY_NAME = Regex("policy\\d+")
        val WHITESPACE = Regex("\\s+")
    }
}
