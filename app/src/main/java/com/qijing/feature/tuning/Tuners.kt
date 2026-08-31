package com.qijing.feature.tuning

import com.qijing.core.execution.CapabilityCommand
import com.qijing.core.execution.ExecutionBroker
import com.qijing.core.execution.ExecutionResult
import com.qijing.core.model.CpuIntent
import com.qijing.core.model.MemoryIntent

class CpuTuner(private val broker: ExecutionBroker) {
    suspend fun apply(intent: CpuIntent): ExecutionResult {
        require(intent.minFrequencyKHz == null || intent.maxFrequencyKHz == null || intent.minFrequencyKHz <= intent.maxFrequencyKHz) { "CPU 最小频率不能高于最大频率" }
        return broker.execute(CapabilityCommand("cpu.apply", mapOf(
            "governor" to (intent.governor ?: ""),
            "minKHz" to (intent.minFrequencyKHz?.toString() ?: ""),
            "maxKHz" to (intent.maxFrequencyKHz?.toString() ?: "")
        )))
    }
}

class MemoryTuner(private val broker: ExecutionBroker) {
    suspend fun apply(intent: MemoryIntent): ExecutionResult {
        require(intent.swappiness == null || intent.swappiness in 0..200) { "swappiness 必须在 0..200" }
        require(intent.zramSizeBytes == null || intent.zramSizeBytes > 0) { "ZRAM 容量必须大于 0" }
        return broker.execute(CapabilityCommand("memory.apply", mapOf(
            "zramEnabled" to (intent.zramEnabled?.toString() ?: ""),
            "zramSizeBytes" to (intent.zramSizeBytes?.toString() ?: ""),
            "algorithm" to (intent.compressionAlgorithm ?: ""),
            "swappiness" to (intent.swappiness?.toString() ?: "")
        )))
    }
}
