package com.qijing.core.device.observation

class MemoryObservationReader(
    private val fileSystem: ObservationFileSystem = LocalObservationFileSystem(),
    private val memInfoPath: String = "/proc/meminfo",
    private val swappinessPath: String = "/proc/sys/vm/swappiness",
    private val blockRoot: String = "/sys/block",
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun read(): MemoryObservation {
        val sampledAt = clock()
        val memInfoResult = fileSystem.readText(memInfoPath)
        val memInfo = (memInfoResult as? FileReadResult.Success)?.let(::parseMemInfo)
        val total = memInfoMetric(memInfoResult, memInfo, "MemTotal", sampledAt)
        val available = memInfoMetric(memInfoResult, memInfo, "MemAvailable", sampledAt)
        val cached = memInfoMetric(memInfoResult, memInfo, "Cached", sampledAt)
        val swapTotal = memInfoMetric(memInfoResult, memInfo, "SwapTotal", sampledAt)
        val swapFree = memInfoMetric(memInfoResult, memInfo, "SwapFree", sampledAt)
        val swapUsed = if (swapTotal.status == MetricStatus.AVAILABLE && swapFree.status == MetricStatus.AVAILABLE) {
            val totalValue = swapTotal.value!!
            val freeValue = swapFree.value!!
            if (freeValue <= totalValue) {
                ObservedMetric.available(totalValue - freeValue, MetricSource.DERIVED, sampledAt, "SwapTotal - SwapFree")
            } else {
                ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.DERIVED, "SwapFree 大于 SwapTotal")
            }
        } else {
            val status = listOf(swapTotal.status, swapFree.status).firstOrNull { it != MetricStatus.UNSUPPORTED }
                ?: MetricStatus.UNSUPPORTED
            ObservedMetric.unavailable(status, sampledAt, MetricSource.PROCFS, "Swap 总量或剩余量不可得")
        }

        val zramList = fileSystem.list(blockRoot)
        val zramNames = (zramList as? DirectoryReadResult.Success)?.names.orEmpty()
            .filter { it.matches(ZRAM_NAME) }
            .sortedBy { it.removePrefix("zram").toIntOrNull() }
        val zramCount = when (zramList) {
            is DirectoryReadResult.Success -> ObservedMetric.available(zramNames.size, MetricSource.SYSFS, sampledAt)
            DirectoryReadResult.Missing -> ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.SYSFS, "未提供块设备目录")
            DirectoryReadResult.PermissionDenied -> ObservedMetric.unavailable(MetricStatus.PERMISSION_DENIED, sampledAt, MetricSource.SYSFS, "当前身份无权枚举 ZRAM")
            is DirectoryReadResult.Failed -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, zramList.detail)
        }

        return MemoryObservation(
            totalBytes = total,
            availableBytes = available,
            cachedBytes = cached,
            swapTotalBytes = swapTotal,
            swapFreeBytes = swapFree,
            swapUsedBytes = swapUsed,
            swappiness = readIntMetric(swappinessPath, sampledAt, MetricSource.PROCFS),
            zramDeviceCount = zramCount,
            zramDevices = zramNames.map { readZram(it, sampledAt) },
            sampledAtMs = sampledAt
        )
    }

    private fun readZram(name: String, sampledAt: Long): ZramObservation {
        val root = "$blockRoot/$name"
        val diskSize = readLongMetric("$root/disksize", sampledAt)
        val mmResult = fileSystem.readText("$root/mm_stat")
        val mmFields = (mmResult as? FileReadResult.Success)?.text?.trim()?.split(WHITESPACE).orEmpty()
        val mmValues = mmFields.map { it.toLongOrNull() }.takeIf { values -> values.all { it != null } }
        fun mmMetric(index: Int, label: String): ObservedMetric<Long> {
            if (mmResult !is FileReadResult.Success) {
                return ObservedMetric.unavailable(mmResult.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, mmResult.detailWhenUnavailable())
            }
            if (mmValues == null) {
                return ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "mm_stat 包含非数值字段")
            }
            return mmValues.getOrNull(index)?.let { ObservedMetric.available(it!!, MetricSource.SYSFS, sampledAt) }
                ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "mm_stat 缺少 $label")
        }

        val algorithmsResult = fileSystem.readText("$root/comp_algorithm")
        val algorithmTokens = (algorithmsResult as? FileReadResult.Success)?.text
            ?.trim()?.split(WHITESPACE)?.filter(String::isNotBlank).orEmpty()
        val algorithms = when (algorithmsResult) {
            is FileReadResult.Success -> {
                val values = algorithmTokens.map { it.removePrefix("[").removeSuffix("]") }.filter(String::isNotBlank).toSet()
                if (values.isEmpty()) ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "压缩算法列表为空")
                else ObservedMetric.available(values, MetricSource.SYSFS, sampledAt)
            }
            else -> ObservedMetric.unavailable(algorithmsResult.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, algorithmsResult.detailWhenUnavailable())
        }
        val currentAlgorithm = when (algorithmsResult) {
            is FileReadResult.Success -> algorithmTokens.firstOrNull { it.startsWith("[") && it.endsWith("]") }
                ?.removePrefix("[")?.removeSuffix("]")
                ?.let { ObservedMetric.available(it, MetricSource.SYSFS, sampledAt) }
                ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "未标记当前压缩算法")
            else -> ObservedMetric.unavailable(algorithmsResult.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, algorithmsResult.detailWhenUnavailable())
        }
        val active = when {
            diskSize.status != MetricStatus.AVAILABLE -> ObservedMetric.unavailable(diskSize.status, sampledAt, MetricSource.SYSFS, diskSize.detail)
            diskSize.value == 0L -> ObservedMetric(MetricStatus.INACTIVE, false, MetricSource.SYSFS, sampledAt, "ZRAM 设备存在但未配置容量")
            else -> ObservedMetric.available(true, MetricSource.SYSFS, sampledAt)
        }
        return ZramObservation(
            device = name,
            active = active,
            diskSizeBytes = diskSize,
            originalDataBytes = mmMetric(0, "orig_data_size"),
            compressedDataBytes = mmMetric(1, "compr_data_size"),
            memoryUsedBytes = mmMetric(2, "mem_used_total"),
            algorithms = algorithms,
            currentAlgorithm = currentAlgorithm
        )
    }

    private fun parseMemInfo(result: FileReadResult.Success): Map<String, Long> =
        result.text.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val key = line.substring(0, separator)
            val fields = line.substring(separator + 1).trim().split(WHITESPACE)
            val raw = fields.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val multiplier = if (fields.getOrNull(1).equals("kB", ignoreCase = true)) 1024L else 1L
            key to raw * multiplier
        }.toMap()

    private fun memInfoMetric(
        result: FileReadResult,
        values: Map<String, Long>?,
        key: String,
        sampledAt: Long
    ): ObservedMetric<Long> {
        if (result !is FileReadResult.Success) {
            return ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.PROCFS, result.detailWhenUnavailable())
        }
        return values?.get(key)?.let { ObservedMetric.available(it, MetricSource.PROCFS, sampledAt) }
            ?: ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.PROCFS, "/proc/meminfo 未提供 $key")
    }

    private fun readLongMetric(path: String, sampledAt: Long): ObservedMetric<Long> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> result.text.trim().toLongOrNull()?.let {
                ObservedMetric.available(it, MetricSource.SYSFS, sampledAt)
            } ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "数值格式无效")
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun readIntMetric(path: String, sampledAt: Long, source: MetricSource): ObservedMetric<Int> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> result.text.trim().toIntOrNull()?.let {
                ObservedMetric.available(it, source, sampledAt)
            } ?: ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, source, "数值格式无效")
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, source, result.detailWhenUnavailable())
        }

    private companion object {
        val ZRAM_NAME = Regex("zram\\d+")
        val WHITESPACE = Regex("\\s+")
    }
}
