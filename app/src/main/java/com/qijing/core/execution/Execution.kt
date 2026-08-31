package com.qijing.core.execution

import com.qijing.core.model.ExecutionBackend

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

interface ExecutionBackendProvider {
    val executionBackend: ExecutionBackend
}

/** Optional preflight contract used to reject a complete plan before the first write. */
interface CommandValidator {
    fun validate(command: CapabilityCommand): ExecutionResult?
}

/** Marks a real write broker that must have a complete read-before-write snapshot. */
interface RequiresRollbackSnapshot

/** First-version safety default. Real backends implement this same contract later. */
class DryRunExecutionBroker : ExecutionBroker, CommandValidator, ExecutionBackendProvider {
    override val executionBackend: ExecutionBackend = ExecutionBackend.DRY_RUN
    override fun validate(command: CapabilityCommand): ExecutionResult? =
        PrivilegedWriteCommandMapper.validationResult(command, "DRY_RUN")

    override suspend fun execute(command: CapabilityCommand): ExecutionResult =
        validate(command) ?: ExecutionResult.Applied(ExecutionBackend.DRY_RUN, "dry-run:${command.capability}")
}
