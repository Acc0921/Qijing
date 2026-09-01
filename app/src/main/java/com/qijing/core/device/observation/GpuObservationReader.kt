package com.qijing.core.device.observation

class GpuObservationReader(
    private val fileSystem: ObservationFileSystem = LocalObservationFileSystem(),
    private val kgslRoot: String = "/sys/class/kgsl/kgsl-3d0",
    private val devfreqRoot: String = "/sys/class/devfreq",
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun read(): GpuObservation {
        val sampledAt = clock()
        val devices = mutableListOf<GpuDeviceObservation>()
        val kgslDirectory = fileSystem.list(kgslRoot)
        if (kgslDirectory is DirectoryReadResult.Success) {
            devices += readKgsl(sampledAt)
        }

        val devfreqDirectory = fileSystem.list(devfreqRoot)
        if (devfreqDirectory is DirectoryReadResult.Success) {
            devfreqDirectory.names
                .filter(::looksLikeGpu)
                .filterNot { devices.isNotEmpty() && it.lowercase().contains("kgsl") }
                .sorted()
                .forEach { devices += readDevfreq(it, sampledAt) }
        }

        if (devices.isNotEmpty()) {
            return GpuObservation(MetricStatus.AVAILABLE, devices, sampledAt)
        }
        val denied = kgslDirectory is DirectoryReadResult.PermissionDenied ||
            devfreqDirectory is DirectoryReadResult.PermissionDenied
        val failed = listOf(kgslDirectory, devfreqDirectory).filterIsInstance<DirectoryReadResult.Failed>().firstOrNull()
        return when {
            denied -> GpuObservation(MetricStatus.PERMISSION_DENIED, emptyList(), sampledAt, "当前身份无权枚举 GPU 观察节点")
            failed != null -> GpuObservation(MetricStatus.INVALID, emptyList(), sampledAt, failed.detail)
            else -> GpuObservation(MetricStatus.UNSUPPORTED, emptyList(), sampledAt, "未发现可识别的 KGSL、Mali 或 GPU devfreq 节点")
        }
    }

    private fun readKgsl(sampledAt: Long): GpuDeviceObservation {
        val devfreq = "$kgslRoot/devfreq"
        return GpuDeviceObservation(
            id = "kgsl-3d0",
            adapter = "qualcomm-kgsl",
            currentFrequencyHz = firstLongMetric(listOf("$devfreq/cur_freq", "$kgslRoot/gpuclk"), sampledAt),
            minFrequencyHz = firstLongMetric(listOf("$devfreq/min_freq", "$kgslRoot/min_gpuclk"), sampledAt),
            maxFrequencyHz = firstLongMetric(listOf("$devfreq/max_freq", "$kgslRoot/max_gpuclk"), sampledAt),
            availableFrequenciesHz = readLongListMetric("$devfreq/available_frequencies", sampledAt),
            loadPercent = readBusyRatio("$kgslRoot/gpubusy", sampledAt),
            governor = readStringMetric("$devfreq/governor", sampledAt)
        )
    }

    private fun readDevfreq(name: String, sampledAt: Long): GpuDeviceObservation {
        val root = "$devfreqRoot/$name"
        return GpuDeviceObservation(
            id = name,
            adapter = when {
                name.lowercase().contains("mali") -> "mali-devfreq"
                name.lowercase().contains("kgsl") -> "qualcomm-devfreq"
                else -> "generic-devfreq"
            },
            currentFrequencyHz = readLongMetric("$root/cur_freq", sampledAt),
            minFrequencyHz = readLongMetric("$root/min_freq", sampledAt),
            maxFrequencyHz = readLongMetric("$root/max_freq", sampledAt),
            availableFrequenciesHz = readLongListMetric("$root/available_frequencies", sampledAt),
            loadPercent = readDevfreqLoad(root, sampledAt),
            governor = readStringMetric("$root/governor", sampledAt)
        )
    }

    private fun readDevfreqLoad(root: String, sampledAt: Long): ObservedMetric<Double> {
        listOf("$root/load", "$root/utilization").forEach { path ->
            val result = fileSystem.readText(path)
            if (result is FileReadResult.Success) {
                val value = result.text.trim().removeSuffix("%").trim().toDoubleOrNull()
                return if (value != null && value in 0.0..100.0) {
                    ObservedMetric.available(value, MetricSource.SYSFS, sampledAt)
                } else {
                    ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "GPU 负载格式或范围无效")
                }
            }
            if (result is FileReadResult.PermissionDenied) {
                return ObservedMetric.unavailable(MetricStatus.PERMISSION_DENIED, sampledAt, MetricSource.SYSFS, "当前身份无权读取 GPU 负载")
            }
        }
        val busy = fileSystem.readText("$root/busy_time")
        val total = fileSystem.readText("$root/total_time")
        if (busy is FileReadResult.Success && total is FileReadResult.Success) {
            return ratioMetric(busy.text, total.text, sampledAt)
        }
        val failure = listOf(busy, total).firstOrNull {
            it is FileReadResult.PermissionDenied || it is FileReadResult.Failed
        }
        return if (failure != null) {
            ObservedMetric.unavailable(failure.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, failure.detailWhenUnavailable())
        } else {
            ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.SYSFS, "驱动未公开 GPU 负载")
        }
    }

    private fun readBusyRatio(path: String, sampledAt: Long): ObservedMetric<Double> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> {
                val values = result.text.trim().split(WHITESPACE).mapNotNull { it.toLongOrNull() }
                if (values.size < 2) {
                    ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "gpubusy 格式无效")
                } else {
                    ratioMetric(values[0].toString(), values[1].toString(), sampledAt)
                }
            }
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun ratioMetric(busyText: String, totalText: String, sampledAt: Long): ObservedMetric<Double> {
        val busy = busyText.trim().toLongOrNull()
        val total = totalText.trim().toLongOrNull()
        if (busy == null || total == null || busy < 0 || total <= 0 || busy > total) {
            return ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "GPU busy/total 计数无效")
        }
        return ObservedMetric.available(busy * 100.0 / total, MetricSource.DERIVED, sampledAt, "由驱动 busy/total 计算", estimated = true)
    }

    private fun firstLongMetric(paths: List<String>, sampledAt: Long): ObservedMetric<Long> {
        var failure: ObservedMetric<Long>? = null
        paths.forEach { path ->
            val value = readLongMetric(path, sampledAt)
            if (value.status == MetricStatus.AVAILABLE) return value
            if (value.status != MetricStatus.UNSUPPORTED) failure = failure ?: value
        }
        return failure ?: ObservedMetric.unavailable(MetricStatus.UNSUPPORTED, sampledAt, MetricSource.SYSFS, "GPU 频率节点不存在")
    }

    private fun readLongMetric(path: String, sampledAt: Long): ObservedMetric<Long> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> when (val value = result.text.trim().toLongOrNull()) {
                null -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "频率格式无效")
                0L -> ObservedMetric.unavailable(MetricStatus.INACTIVE, sampledAt, MetricSource.SYSFS, "GPU 当前未活动或驱动未上报频率")
                in Long.MIN_VALUE until 0L -> ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "频率不能为负值")
                else -> ObservedMetric.available(value, MetricSource.SYSFS, sampledAt)
            }
            else -> ObservedMetric.unavailable(result.statusWhenUnavailable(), sampledAt, MetricSource.SYSFS, result.detailWhenUnavailable())
        }

    private fun readLongListMetric(path: String, sampledAt: Long): ObservedMetric<List<Long>> =
        when (val result = fileSystem.readText(path)) {
            is FileReadResult.Success -> {
                val fields = result.text.trim().split(WHITESPACE).filter(String::isNotBlank)
                val values = fields.mapNotNull { it.toLongOrNull() }.distinct().sorted()
                if (values.isEmpty() || values.size != fields.size || values.any { it <= 0L }) {
                    ObservedMetric.unavailable(MetricStatus.INVALID, sampledAt, MetricSource.SYSFS, "可用频率列表无效")
                } else ObservedMetric.available(values, MetricSource.SYSFS, sampledAt)
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

    private fun looksLikeGpu(name: String): Boolean {
        val lower = name.lowercase()
        return (lower.contains("gpu") || lower.contains("kgsl") || lower.contains("mali") || lower.contains("3d")) &&
            !lower.contains("gpubw")
    }

    private companion object { val WHITESPACE = Regex("\\s+") }
}
