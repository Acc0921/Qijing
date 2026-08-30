package com.scenepilot.core.logging

data class TaskLog(val taskId: String, val stage: String, val message: String, val success: Boolean, val timestampMs: Long)

interface TaskLogStore { fun append(log: TaskLog); fun recent(limit: Int = 100): List<TaskLog> }

class InMemoryTaskLogStore : TaskLogStore {
    private val logs = ArrayDeque<TaskLog>()
    override fun append(log: TaskLog) { logs.addLast(log); while (logs.size > 500) logs.removeFirst() }
    override fun recent(limit: Int) = logs.toList().takeLast(limit)
}
