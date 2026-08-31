package com.scenepilot.feature.tuning

import com.scenepilot.core.scene.CapabilityValueReader
import java.io.File

data class CpuStatus(val onlineCores: Int, val governors: Set<String>, val minFrequencyKHz: Long?, val maxFrequencyKHz: Long?)
data class MemoryStatus(val totalBytes: Long?, val availableBytes: Long?, val zramSizeBytes: Long?, val zramAlgorithms: Set<String>)

class CpuStatusReader(private val root: File = File("/sys/devices/system/cpu")) {
    fun read(): CpuStatus {
        val cores = root.listFiles()?.filter { it.name.matches(Regex("cpu\\d+")) } ?: emptyList()
        val governors = cores.mapNotNull { it.resolve("cpufreq/scaling_available_governors").readTextOrNull() }.flatMap { it.trim().split(Regex("\\s+")) }.filter(String::isNotBlank).toSet()
        val mins = cores.mapNotNull { it.resolve("cpufreq/cpuinfo_min_freq").readLongOrNull() }
        val maxes = cores.mapNotNull { it.resolve("cpufreq/cpuinfo_max_freq").readLongOrNull() }
        return CpuStatus(cores.count { it.resolve("online").readTextOrNull()?.trim() != "0" }, governors, mins.minOrNull(), maxes.maxOrNull())
    }
}

class MemoryStatusReader(private val memInfo: File = File("/proc/meminfo"), private val zram: File = File("/sys/block/zram0")) {
    fun read(): MemoryStatus {
        val values = memInfo.readTextOrNull().orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(Regex("\\s+")); if (parts.size >= 2) parts[0].removeSuffix(":") to parts[1].toLongOrNull()?.times(1024) else null
        }.toMap()
        val algorithms = zram.resolve("comp_algorithm").readTextOrNull()?.split(Regex("\\s+"))?.filter { it.isNotBlank() && !it.startsWith("[") }?.toSet() ?: emptySet()
        return MemoryStatus(values["MemTotal"], values["MemAvailable"], zram.resolve("disksize").readLongOrNull(), algorithms)
    }
}

class SysfsCapabilityValueReader : CapabilityValueReader {
    override fun read(capability: String): String? = when (capability) {
        "cpu.governor.set" -> File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").readTextOrNull()?.trim()
        "memory.swappiness.set" -> File("/proc/sys/vm/swappiness").readTextOrNull()?.trim()
        "memory.zram.size" -> File("/sys/block/zram0/disksize").readLongOrNull()?.toString()
        else -> null
    }
}

private fun File.readTextOrNull(): String? = runCatching { if (canRead()) readText() else null }.getOrNull()
private fun File.readLongOrNull(): Long? = readTextOrNull()?.trim()?.toLongOrNull()
