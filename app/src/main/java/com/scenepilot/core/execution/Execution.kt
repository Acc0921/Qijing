package com.scenepilot.core.execution

import com.scenepilot.core.model.ExecutionBackend

data class CapabilityCommand(
    val capability: String,
    val arguments: Map<String, String> = emptyMap(),
    val rollback: CapabilityCommand? = null
)

sealed interface ExecutionResult {
    data class Applied(val backend: ExecutionBackend, val output: String = "") : ExecutionResult
    data class Unsupported(val capability: String, val reason: String) : ExecutionResult
    data class Failed(val code: String, val message: String, val rollback: CapabilityCommand? = null) : ExecutionResult
}

interface ExecutionBroker {
    suspend fun execute(command: CapabilityCommand): ExecutionResult
}

/** First-version safety default. Real backends implement this same contract later. */
class DryRunExecutionBroker : ExecutionBroker {
    override suspend fun execute(command: CapabilityCommand): ExecutionResult =
        ExecutionResult.Applied(ExecutionBackend.DRY_RUN, "dry-run:${command.capability}")
}
