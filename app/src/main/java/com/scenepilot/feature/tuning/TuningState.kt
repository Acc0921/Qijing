package com.scenepilot.feature.tuning

import com.scenepilot.core.execution.ExecutionResult
import com.scenepilot.core.model.CpuIntent
import com.scenepilot.core.model.MemoryIntent

sealed interface TuningStatus {
    data object Idle : TuningStatus
    data object Applying : TuningStatus
    data class Applied(val backend: String) : TuningStatus
    data class Failed(val message: String) : TuningStatus
}

data class CpuTuningState(val intent: CpuIntent = CpuIntent(), val status: TuningStatus = TuningStatus.Idle)
data class MemoryTuningState(val intent: MemoryIntent = MemoryIntent(), val status: TuningStatus = TuningStatus.Idle)

class CpuTuningController(private val tuner: CpuTuner) {
    var state: CpuTuningState = CpuTuningState(); private set
    suspend fun apply(intent: CpuIntent): CpuTuningState {
        state = CpuTuningState(intent, TuningStatus.Applying)
        state = CpuTuningState(intent, resultStatus(tuner.apply(intent)))
        return state
    }
}

class MemoryTuningController(private val tuner: MemoryTuner) {
    var state: MemoryTuningState = MemoryTuningState(); private set
    suspend fun apply(intent: MemoryIntent): MemoryTuningState {
        state = MemoryTuningState(intent, TuningStatus.Applying)
        state = MemoryTuningState(intent, resultStatus(tuner.apply(intent)))
        return state
    }
}

private fun resultStatus(result: ExecutionResult): TuningStatus = when (result) {
    is ExecutionResult.Applied -> TuningStatus.Applied(result.backend.name)
    is ExecutionResult.Unsupported -> TuningStatus.Failed(result.reason)
    is ExecutionResult.Failed -> TuningStatus.Failed(result.message)
}
