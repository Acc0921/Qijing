package com.qijing.core.execution

/** A validated, ordered set of commands. The UI never constructs shell text. */
data class CommandPlan(val id: String, val commands: List<CapabilityCommand>) {
    init { require(commands.none { it.capability.isBlank() }) { "command capability 不能为空" } }
}

data class TransactionResult(
    val plan: CommandPlan,
    val applied: List<ExecutionResult.Applied>,
    val failure: ExecutionResult? = null,
    val rolledBack: Boolean = false
)
