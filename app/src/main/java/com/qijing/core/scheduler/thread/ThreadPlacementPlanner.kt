package com.qijing.core.scheduler.thread

import com.qijing.core.scheduler.CpuSet
import com.qijing.core.scheduler.ThreadPlacementRuleSet
import java.security.MessageDigest

/** A process identity remains valid only while both pid and startTimeTicks still match /proc. */
data class RunningProcessIdentity(
    val pid: Int,
    val startTimeTicks: Long,
    val packageName: String
)

/** A thread identity is tied to its owning process to prevent cross-process TID reuse. */
data class RunningThreadIdentity(
    val process: RunningProcessIdentity,
    val tid: Int,
    val startTimeTicks: Long
)

enum class ThreadSchedulingPolicy {
    OTHER,
    FIFO,
    ROUND_ROBIN,
    BATCH,
    IDLE,
    DEADLINE
}

data class ThreadSchedulingState(
    val policy: ThreadSchedulingPolicy,
    val priority: Int
)

/**
 * Logical cpuset placement. The planner never treats [groupId] as a filesystem path or command.
 * Managed groups use a stable qijing:* key so a second observation can be planned idempotently.
 */
data class ThreadCpuSetPlacement(
    val groupId: String,
    val allowedCpus: CpuSet,
    val managedByQijing: Boolean = false
) {
    companion object {
        fun managed(cpuSet: CpuSet): ThreadCpuSetPlacement = ThreadCpuSetPlacement(
            groupId = "qijing:${cpuSet.stableKey()}",
            allowedCpus = cpuSet,
            managedByQijing = true
        )

        private fun CpuSet.stableKey(): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class RunningThread(
    val tid: Int,
    val startTimeTicks: Long,
    val name: String,
    val affinity: CpuSet,
    val cpuSetPlacement: ThreadCpuSetPlacement,
    val scheduling: ThreadSchedulingState,
    val nice: Int
)

data class RunningProcess(
    val pid: Int,
    val startTimeTicks: Long,
    val packageName: String,
    val threads: List<RunningThread>
)

data class RunningProcessSnapshot(
    val capturedAtElapsedRealtimeMs: Long,
    val availableCores: Set<Int>,
    val processes: List<RunningProcess>
)

data class ThreadPlacementTuning(
    val roundRobinPriority: Int = 1,
    val adjustedNice: Int = -10,
    /** Positive nice value used for threads explicitly classified by threads.json trashy. */
    val trashyNice: Int = 10
)

enum class ThreadMutationKind {
    CPUSET,
    AFFINITY,
    NICE,
    SCHEDULER
}

/**
 * Every mutation contains its expected current value. An executor must re-read both identities and
 * [expected] immediately before applying [target]; otherwise the mutation is stale and must fail.
 */
sealed interface ThreadPlacementMutation {
    val identity: RunningThreadIdentity
    val profileLabel: String
    val threadName: String
    val kind: ThreadMutationKind

    data class SetCpuSet(
        override val identity: RunningThreadIdentity,
        override val profileLabel: String,
        override val threadName: String,
        val expected: ThreadCpuSetPlacement,
        val target: ThreadCpuSetPlacement
    ) : ThreadPlacementMutation {
        override val kind = ThreadMutationKind.CPUSET
    }

    data class SetAffinity(
        override val identity: RunningThreadIdentity,
        override val profileLabel: String,
        override val threadName: String,
        val expected: CpuSet,
        val target: CpuSet
    ) : ThreadPlacementMutation {
        override val kind = ThreadMutationKind.AFFINITY
    }

    data class SetNice(
        override val identity: RunningThreadIdentity,
        override val profileLabel: String,
        override val threadName: String,
        val expected: Int,
        val target: Int
    ) : ThreadPlacementMutation {
        override val kind = ThreadMutationKind.NICE
    }

    data class SetScheduler(
        override val identity: RunningThreadIdentity,
        override val profileLabel: String,
        override val threadName: String,
        val expected: ThreadSchedulingState,
        val target: ThreadSchedulingState
    ) : ThreadPlacementMutation {
        override val kind = ThreadMutationKind.SCHEDULER
    }
}

data class ThreadPlacementChange(
    val apply: ThreadPlacementMutation,
    /** Restore is guarded by the value applied by [apply], as well as the same process/thread identity. */
    val restore: ThreadPlacementMutation
)

enum class ThreadPlacementDiagnosticCode {
    NO_MATCHING_PROCESS,
    PROCESS_HAS_NO_THREADS,
    NO_MUTATION_REQUIRED
}

data class ThreadPlacementDiagnostic(
    val code: ThreadPlacementDiagnosticCode,
    val message: String,
    val pid: Int? = null
)

data class ThreadPlacementPlan(
    val sourceCapturedAtElapsedRealtimeMs: Long,
    val matchedProcessCount: Int,
    val matchedThreadCount: Int,
    val changes: List<ThreadPlacementChange>,
    val diagnostics: List<ThreadPlacementDiagnostic>
) {
    val applyMutations: List<ThreadPlacementMutation> = changes.map { it.apply }
    val restoreMutations: List<ThreadPlacementMutation> = changes.asReversed().map { it.restore }
}

enum class ThreadPlacementRejectionCode {
    INVALID_AVAILABLE_CORES,
    INVALID_TUNING,
    INVALID_PROCESS,
    INVALID_THREAD,
    INCOMPLETE_CPU_STATE,
    DUPLICATE_PROCESS_IDENTITY,
    DUPLICATE_PID,
    DUPLICATE_TID,
    CONFLICTING_THREAD_SNAPSHOT,
    UNAVAILABLE_TARGET_CPU,
    CONFLICTING_MUTATION
}

data class ThreadPlacementRejection(
    val code: ThreadPlacementRejectionCode,
    val message: String,
    val pid: Int? = null,
    val tid: Int? = null
)

sealed interface ThreadPlacementPlanResult {
    data class Planned(val plan: ThreadPlacementPlan) : ThreadPlacementPlanResult
    data class Rejected(val reasons: List<ThreadPlacementRejection>) : ThreadPlacementPlanResult
}

/** Pure planner: converts an immutable /proc snapshot and rules into typed, reversible mutations. */
class ThreadPlacementPlanner {
    fun plan(
        ruleSet: ThreadPlacementRuleSet,
        snapshot: RunningProcessSnapshot,
        tuning: ThreadPlacementTuning = ThreadPlacementTuning()
    ): ThreadPlacementPlanResult {
        val validation = validate(snapshot, tuning)
        if (validation.isNotEmpty()) return ThreadPlacementPlanResult.Rejected(validation)

        val invalidTargets = snapshot.processes.flatMap { process ->
            process.threads.mapNotNull { thread ->
                val decision = ruleSet.decide(
                    process.packageName,
                    thread.name,
                    isProcessMainThread = thread.tid == process.pid
                ) ?: return@mapNotNull null
                if (decision.cpuSet.cores.all { it in snapshot.availableCores }) null else rejection(
                    ThreadPlacementRejectionCode.UNAVAILABLE_TARGET_CPU,
                    "A rule targets a CPU that is unavailable in this snapshot",
                    process.pid,
                    thread.tid
                )
            }
        }.distinct()
        if (invalidTargets.isNotEmpty()) return ThreadPlacementPlanResult.Rejected(invalidTargets)

        val changes = mutableListOf<ThreadPlacementChange>()
        val diagnostics = mutableListOf<ThreadPlacementDiagnostic>()
        var matchedProcesses = 0
        var matchedThreads = 0

        snapshot.processes
            .sortedWith(compareBy<RunningProcess> { it.pid }.thenBy { it.startTimeTicks })
            .forEach { process ->
                val matchedProfile = ruleSet.profiles.firstOrNull { process.packageName in it.packageNames }
                    ?: return@forEach
                matchedProcesses++
                if (process.threads.isEmpty()) {
                    diagnostics += ThreadPlacementDiagnostic(
                        ThreadPlacementDiagnosticCode.PROCESS_HAS_NO_THREADS,
                        "Matched process has no observed threads for ${matchedProfile.label}",
                        process.pid
                    )
                    return@forEach
                }
                val processDecisions = process.threads.mapNotNull { thread ->
                    ruleSet.decide(
                        process.packageName,
                        thread.name,
                        isProcessMainThread = thread.tid == process.pid
                    )?.let { thread to it }
                }
                processDecisions.sortedBy { it.first.tid }.forEach { (thread, decision) ->
                    matchedThreads++
                    val processIdentity = RunningProcessIdentity(process.pid, process.startTimeTicks, process.packageName)
                    val identity = RunningThreadIdentity(processIdentity, thread.tid, thread.startTimeTicks)
                    val targetCpuSetPlacement = ThreadCpuSetPlacement.managed(decision.cpuSet)

                    if (!sameCpuSetPlacement(thread.cpuSetPlacement, targetCpuSetPlacement)) {
                        changes += change(
                            ThreadPlacementMutation.SetCpuSet(
                                identity, decision.profileLabel, thread.name,
                                expected = thread.cpuSetPlacement,
                                target = targetCpuSetPlacement
                            )
                        )
                    }
                    if (thread.affinity.canonical != decision.cpuSet.canonical) {
                        changes += change(
                            ThreadPlacementMutation.SetAffinity(
                                identity, decision.profileLabel, thread.name,
                                expected = thread.affinity,
                                target = decision.cpuSet
                            )
                        )
                    }
                    val targetNice = when {
                        decision.requestTrashyDemotion -> tuning.trashyNice
                        decision.requestNiceAdjustment -> tuning.adjustedNice
                        else -> null
                    }
                    if (targetNice != null && thread.nice != targetNice) {
                        changes += change(
                            ThreadPlacementMutation.SetNice(
                                identity, decision.profileLabel, thread.name,
                                expected = thread.nice,
                                target = targetNice
                            )
                        )
                    }
                    val rrTarget = ThreadSchedulingState(
                        ThreadSchedulingPolicy.ROUND_ROBIN,
                        tuning.roundRobinPriority
                    )
                    if (decision.requestRoundRobin && thread.scheduling != rrTarget) {
                        changes += change(
                            ThreadPlacementMutation.SetScheduler(
                                identity, decision.profileLabel, thread.name,
                                expected = thread.scheduling,
                                target = rrTarget
                            )
                        )
                    }
                }
            }

        if (matchedProcesses == 0) {
            diagnostics += ThreadPlacementDiagnostic(
                ThreadPlacementDiagnosticCode.NO_MATCHING_PROCESS,
                "No running process matched the configured packages"
            )
        } else if (changes.isEmpty()) {
            diagnostics += ThreadPlacementDiagnostic(
                ThreadPlacementDiagnosticCode.NO_MUTATION_REQUIRED,
                "All matched threads already have the requested placement"
            )
        }

        val ordered = changes
            .distinctBy { it.apply.mutationKey() }
            .sortedWith(
                compareBy<ThreadPlacementChange> { it.apply.identity.process.pid }
                    .thenBy { it.apply.identity.tid }
                    .thenBy { it.apply.kind.ordinal }
            )
        val conflicts = findMutationConflicts(changes)
        if (conflicts.isNotEmpty()) return ThreadPlacementPlanResult.Rejected(conflicts)

        return ThreadPlacementPlanResult.Planned(
            ThreadPlacementPlan(
                sourceCapturedAtElapsedRealtimeMs = snapshot.capturedAtElapsedRealtimeMs,
                matchedProcessCount = matchedProcesses,
                matchedThreadCount = matchedThreads,
                changes = ordered,
                diagnostics = diagnostics
            )
        )
    }

    private fun validate(
        snapshot: RunningProcessSnapshot,
        tuning: ThreadPlacementTuning
    ): List<ThreadPlacementRejection> {
        val rejected = mutableListOf<ThreadPlacementRejection>()
        if (snapshot.capturedAtElapsedRealtimeMs < 0L ||
            snapshot.availableCores.isEmpty() ||
            snapshot.availableCores.any { it !in MIN_CPU..MAX_CPU }
        ) {
            rejected += rejection(ThreadPlacementRejectionCode.INVALID_AVAILABLE_CORES, "Available CPU set is invalid")
        }
        if (tuning.roundRobinPriority !in MIN_RT_PRIORITY..MAX_RT_PRIORITY ||
            tuning.adjustedNice !in MIN_NICE..MAX_NICE ||
            tuning.trashyNice !in MIN_TRASHY_NICE..MAX_NICE
        ) {
            rejected += rejection(
                ThreadPlacementRejectionCode.INVALID_TUNING,
                "RR priority or nice targets are outside kernel bounds"
            )
        }

        val pids = mutableSetOf<Int>()
        val processIdentities = mutableSetOf<Pair<Int, Long>>()
        val tids = mutableMapOf<Int, RunningThread>()
        snapshot.processes.forEach { process ->
            if (process.pid !in MIN_TASK_ID..MAX_TASK_ID || process.startTimeTicks <= 0L ||
                !PACKAGE_NAME.matches(process.packageName)
            ) {
                rejected += rejection(ThreadPlacementRejectionCode.INVALID_PROCESS, "Process identity is invalid", process.pid)
            }
            if (!pids.add(process.pid)) {
                rejected += rejection(ThreadPlacementRejectionCode.DUPLICATE_PID, "PID occurs more than once in one snapshot", process.pid)
            }
            if (!processIdentities.add(process.pid to process.startTimeTicks)) {
                rejected += rejection(ThreadPlacementRejectionCode.DUPLICATE_PROCESS_IDENTITY, "Process identity is duplicated", process.pid)
            }
            process.threads.forEach { thread ->
                if (thread.tid !in MIN_TASK_ID..MAX_TASK_ID || thread.startTimeTicks <= 0L ||
                    thread.name.isBlank() || thread.name.length > MAX_THREAD_NAME_LENGTH || '\u0000' in thread.name ||
                    thread.nice !in MIN_NICE..MAX_NICE || !thread.scheduling.isValid()
                ) {
                    rejected += rejection(ThreadPlacementRejectionCode.INVALID_THREAD, "Thread state is invalid", process.pid, thread.tid)
                }
                val previous = tids[thread.tid]
                if (previous != null) {
                    val code = if (previous == thread) {
                        ThreadPlacementRejectionCode.DUPLICATE_TID
                    } else {
                        ThreadPlacementRejectionCode.CONFLICTING_THREAD_SNAPSHOT
                    }
                    rejected += rejection(code, "TID occurs more than once in one snapshot", process.pid, thread.tid)
                } else {
                    tids[thread.tid] = thread
                }
                if (!thread.affinity.isWithin(snapshot.availableCores) ||
                    !thread.cpuSetPlacement.allowedCpus.isWithin(snapshot.availableCores) ||
                    thread.cpuSetPlacement.groupId.isBlank() ||
                    thread.cpuSetPlacement.groupId.length > MAX_GROUP_ID_LENGTH ||
                    '\u0000' in thread.cpuSetPlacement.groupId
                ) {
                    rejected += rejection(
                        ThreadPlacementRejectionCode.INCOMPLETE_CPU_STATE,
                        "Thread affinity or cpuset state cannot be restored safely",
                        process.pid,
                        thread.tid
                    )
                }
            }
        }
        return rejected.distinct()
    }

    private fun findMutationConflicts(changes: List<ThreadPlacementChange>): List<ThreadPlacementRejection> =
        changes.groupBy { it.apply.mutationKey() }
            .filterValues { duplicates -> duplicates.map { it.apply }.distinct().size > 1 }
            .map { (_, duplicates) ->
                val mutation = duplicates.first().apply
                rejection(
                    ThreadPlacementRejectionCode.CONFLICTING_MUTATION,
                    "More than one target was generated for the same thread property",
                    mutation.identity.process.pid,
                    mutation.identity.tid
                )
            }

    private fun change(apply: ThreadPlacementMutation): ThreadPlacementChange = ThreadPlacementChange(
        apply = apply,
        restore = apply.inverse()
    )

    private fun ThreadPlacementMutation.inverse(): ThreadPlacementMutation = when (this) {
        is ThreadPlacementMutation.SetCpuSet -> copy(expected = target, target = expected)
        is ThreadPlacementMutation.SetAffinity -> copy(expected = target, target = expected)
        is ThreadPlacementMutation.SetNice -> copy(expected = target, target = expected)
        is ThreadPlacementMutation.SetScheduler -> copy(expected = target, target = expected)
    }

    private fun ThreadPlacementMutation.mutationKey(): MutationKey = MutationKey(identity, kind)

    private fun sameCpuSetPlacement(
        current: ThreadCpuSetPlacement,
        target: ThreadCpuSetPlacement
    ): Boolean = current.managedByQijing &&
        current.groupId == target.groupId &&
        current.allowedCpus.canonical == target.allowedCpus.canonical

    private fun ThreadSchedulingState.isValid(): Boolean = when (policy) {
        ThreadSchedulingPolicy.FIFO, ThreadSchedulingPolicy.ROUND_ROBIN -> priority in MIN_RT_PRIORITY..MAX_RT_PRIORITY
        else -> priority == 0
    }

    private fun CpuSet.isWithin(available: Set<Int>): Boolean = cores.isNotEmpty() && cores.all { it in available }

    private fun rejection(
        code: ThreadPlacementRejectionCode,
        message: String,
        pid: Int? = null,
        tid: Int? = null
    ) = ThreadPlacementRejection(code, message, pid, tid)

    private companion object {
        const val MIN_CPU = 0
        const val MAX_CPU = 255
        const val MIN_TASK_ID = 1
        const val MAX_TASK_ID = 4_194_304
        const val MIN_NICE = -20
        const val MAX_NICE = 19
        const val MIN_TRASHY_NICE = 1
        const val MIN_RT_PRIORITY = 1
        const val MAX_RT_PRIORITY = 99
        const val MAX_THREAD_NAME_LENGTH = 256
        const val MAX_GROUP_ID_LENGTH = 256
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }

    private data class MutationKey(
        val identity: RunningThreadIdentity,
        val kind: ThreadMutationKind
    )
}
