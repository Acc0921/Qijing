package com.qijing.core.scheduler.thread

import com.qijing.core.scheduler.CpuSet
import com.qijing.core.scheduler.ThreadPlacementJsonParser
import com.qijing.core.scheduler.ThreadPlacementLoad
import com.qijing.core.scheduler.ThreadPlacementRuleSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadPlacementPlannerTest {
    @Test
    fun `plans deterministic cpuset affinity nice and RR changes with guarded reverse restore`() {
        val process = process(
            threads = listOf(
                thread(101, "MainThread"),
                thread(102, "RenderThread"),
                thread(103, "Audio")
            )
        )

        val planned = planner.plan(rules(), snapshot(process)) as ThreadPlacementPlanResult.Planned
        val plan = planned.plan

        assertEquals(1, plan.matchedProcessCount)
        assertEquals(3, plan.matchedThreadCount)
        assertEquals(8, plan.changes.size)
        assertEquals(
            listOf(
                ThreadMutationKind.CPUSET,
                ThreadMutationKind.AFFINITY,
                ThreadMutationKind.NICE,
                ThreadMutationKind.CPUSET,
                ThreadMutationKind.AFFINITY,
                ThreadMutationKind.SCHEDULER,
                ThreadMutationKind.CPUSET,
                ThreadMutationKind.AFFINITY
            ),
            plan.applyMutations.map { it.kind }
        )

        val mainCpuSet = plan.applyMutations[0] as ThreadPlacementMutation.SetCpuSet
        assertEquals("/top-app", mainCpuSet.expected.groupId)
        assertTrue(mainCpuSet.target.groupId.startsWith("qijing:"))
        assertEquals("7", mainCpuSet.target.allowedCpus.canonical)
        assertTrue(mainCpuSet.target.managedByQijing)

        val mainNice = plan.applyMutations[2] as ThreadPlacementMutation.SetNice
        assertEquals(0, mainNice.expected)
        assertEquals(-10, mainNice.target)

        val renderAffinity = plan.applyMutations[4] as ThreadPlacementMutation.SetAffinity
        assertEquals("6", renderAffinity.target.canonical)
        val renderScheduler = plan.applyMutations[5] as ThreadPlacementMutation.SetScheduler
        assertEquals(ThreadSchedulingState(ThreadSchedulingPolicy.ROUND_ROBIN, 1), renderScheduler.target)

        assertEquals(plan.applyMutations.map { it.kind }.reversed(), plan.restoreMutations.map { it.kind })
        val restoreMainCpuSet = plan.restoreMutations.last() as ThreadPlacementMutation.SetCpuSet
        assertEquals(mainCpuSet.target, restoreMainCpuSet.expected)
        assertEquals(mainCpuSet.expected, restoreMainCpuSet.target)
        assertEquals(mainCpuSet.identity, restoreMainCpuSet.identity)
    }

    @Test
    fun `second observation of applied state is idempotent and emits no actions`() {
        assertEquals(cpu("0-3"), cpu("0,1,2,3"))
        val process = process(
            threads = listOf(
                thread(
                    tid = 101,
                    name = "MainThread",
                    affinity = cpu("7"),
                    placement = ThreadCpuSetPlacement.managed(cpu("7")),
                    nice = -10
                ),
                thread(
                    tid = 102,
                    name = "RenderThread",
                    affinity = cpu("6"),
                    placement = ThreadCpuSetPlacement.managed(cpu("6")),
                    scheduling = ThreadSchedulingState(ThreadSchedulingPolicy.ROUND_ROBIN, 1)
                ),
                thread(
                    tid = 103,
                    name = "Audio",
                    affinity = cpu("0-3"),
                    placement = ThreadCpuSetPlacement.managed(cpu("0-3"))
                )
            )
        )

        val result = planner.plan(rules(), snapshot(process)) as ThreadPlacementPlanResult.Planned

        assertTrue(result.plan.changes.isEmpty())
        assertEquals(
            listOf(ThreadPlacementDiagnosticCode.NO_MUTATION_REQUIRED),
            result.plan.diagnostics.map { it.code }
        )
    }

    @Test
    fun `unmatched and not-yet-threaded processes remain safe diagnostics rather than writes`() {
        val unmatched = process(pid = 200, packageName = "com.example.other")
        val matchedWithoutThreads = process(pid = 201, threads = emptyList())

        val result = planner.plan(rules(), snapshot(unmatched, matchedWithoutThreads)) as ThreadPlacementPlanResult.Planned

        assertEquals(1, result.plan.matchedProcessCount)
        assertEquals(0, result.plan.matchedThreadCount)
        assertTrue(result.plan.changes.isEmpty())
        assertEquals(
            listOf(ThreadPlacementDiagnosticCode.PROCESS_HAS_NO_THREADS, ThreadPlacementDiagnosticCode.NO_MUTATION_REQUIRED),
            result.plan.diagnostics.map { it.code }
        )
    }

    @Test
    fun `empty snapshot reports that no configured package is running`() {
        val result = planner.plan(rules(), snapshot()) as ThreadPlacementPlanResult.Planned

        assertEquals(0, result.plan.matchedProcessCount)
        assertEquals(ThreadPlacementDiagnosticCode.NO_MATCHING_PROCESS, result.plan.diagnostics.single().code)
    }

    @Test
    fun `identity guards distinguish process and TID reuse and survive into restore`() {
        val beforeReuse = planner.plan(
            rules(),
            snapshot(process(pid = 300, startTime = 1_000, threads = listOf(thread(301, "Audio", startTime = 1_001))))
        ) as ThreadPlacementPlanResult.Planned
        val afterReuse = planner.plan(
            rules(),
            snapshot(process(pid = 300, startTime = 2_000, threads = listOf(thread(301, "Audio", startTime = 2_001))))
        ) as ThreadPlacementPlanResult.Planned

        val oldApply = beforeReuse.plan.applyMutations.first()
        val oldRestore = beforeReuse.plan.restoreMutations.last()
        val reusedApply = afterReuse.plan.applyMutations.first()
        assertEquals(1_000, oldApply.identity.process.startTimeTicks)
        assertEquals(1_001, oldApply.identity.startTimeTicks)
        assertEquals(oldApply.identity, oldRestore.identity)
        assertNotEquals(oldApply.identity, reusedApply.identity)
    }

    @Test
    fun `rejects duplicated or conflicting task identities before planning`() {
        val shared = thread(401, "Audio")
        val duplicate = planner.plan(
            rules(),
            snapshot(
                process(pid = 400, threads = listOf(shared)),
                process(pid = 402, packageName = "com.example.other", threads = listOf(shared))
            )
        ) as ThreadPlacementPlanResult.Rejected
        val conflict = planner.plan(
            rules(),
            snapshot(
                process(pid = 410, threads = listOf(thread(411, "Audio", startTime = 500))),
                process(pid = 412, packageName = "com.example.other", threads = listOf(thread(411, "Worker", startTime = 501)))
            )
        ) as ThreadPlacementPlanResult.Rejected

        assertTrue(duplicate.reasons.any { it.code == ThreadPlacementRejectionCode.DUPLICATE_TID })
        assertTrue(conflict.reasons.any { it.code == ThreadPlacementRejectionCode.CONFLICTING_THREAD_SNAPSHOT })
    }

    @Test
    fun `rejects duplicate PIDs and task IDs outside kernel bounds`() {
        val duplicatePid = planner.plan(
            rules(),
            snapshot(process(pid = 420), process(pid = 420, startTime = 2_000, packageName = "com.example.other"))
        ) as ThreadPlacementPlanResult.Rejected
        val invalidTaskIds = planner.plan(
            rules(),
            snapshot(process(pid = 0, threads = listOf(thread(4_194_305, "Audio"))))
        ) as ThreadPlacementPlanResult.Rejected

        assertTrue(duplicatePid.reasons.any { it.code == ThreadPlacementRejectionCode.DUPLICATE_PID })
        assertTrue(invalidTaskIds.reasons.any { it.code == ThreadPlacementRejectionCode.INVALID_PROCESS })
        assertTrue(invalidTaskIds.reasons.any { it.code == ThreadPlacementRejectionCode.INVALID_THREAD })
    }

    @Test
    fun `rejects unavailable target CPUs incomplete snapshots and invalid tuning bounds`() {
        val unavailableTarget = planner.plan(
            rules(availableCores = 0..7),
            snapshot(
                process(threads = listOf(thread(101, "MainThread", affinity = cpu("0-3"), placement = placement("0-3")))),
                availableCores = (0..3).toSet()
            )
        ) as ThreadPlacementPlanResult.Rejected
        val incomplete = planner.plan(
            rules(),
            snapshot(
                process(threads = listOf(thread(101, "Audio", affinity = cpu("8")))),
                availableCores = (0..7).toSet()
            )
        ) as ThreadPlacementPlanResult.Rejected
        val invalidTuning = planner.plan(
            rules(),
            snapshot(process()),
            ThreadPlacementTuning(roundRobinPriority = 100, adjustedNice = -21)
        ) as ThreadPlacementPlanResult.Rejected

        assertTrue(unavailableTarget.reasons.any { it.code == ThreadPlacementRejectionCode.UNAVAILABLE_TARGET_CPU })
        assertTrue(incomplete.reasons.any { it.code == ThreadPlacementRejectionCode.INCOMPLETE_CPU_STATE })
        assertTrue(invalidTuning.reasons.any { it.code == ThreadPlacementRejectionCode.INVALID_TUNING })
    }

    @Test
    fun `accepts kernel task nice and realtime priority boundary values`() {
        val boundaryThread = thread(
            tid = 4_194_304,
            name = "RenderThread",
            scheduling = ThreadSchedulingState(ThreadSchedulingPolicy.ROUND_ROBIN, 99),
            nice = -20
        )
        val result = planner.plan(
            rules(),
            snapshot(process(pid = 4_194_304, threads = listOf(boundaryThread))),
            ThreadPlacementTuning(roundRobinPriority = 99, adjustedNice = 19)
        )

        assertTrue(result is ThreadPlacementPlanResult.Planned)
    }

    @Test
    fun `trashy thread receives explicit fallback CPUs and reversible low priority nice`() {
        val rules = parseRules(
            """[{
              "friendly":"Sample game","packages":["com.example.game"],
              "cpuset":{"trashy":["Async*"],"other":"0-3"}
            }]"""
        )
        val result = planner.plan(
            rules,
            snapshot(process(threads = listOf(thread(101, "AsyncWorker"))))
        ) as ThreadPlacementPlanResult.Planned

        assertEquals(
            listOf(ThreadMutationKind.CPUSET, ThreadMutationKind.AFFINITY, ThreadMutationKind.NICE),
            result.plan.applyMutations.map { it.kind }
        )
        val cpuSet = result.plan.applyMutations[0] as ThreadPlacementMutation.SetCpuSet
        val affinity = result.plan.applyMutations[1] as ThreadPlacementMutation.SetAffinity
        val nice = result.plan.applyMutations[2] as ThreadPlacementMutation.SetNice
        assertEquals("0,1,2,3", cpuSet.target.allowedCpus.canonical)
        assertEquals("0,1,2,3", affinity.target.canonical)
        assertEquals(10, nice.target)

        val restoreNice = result.plan.restoreMutations.first() as ThreadPlacementMutation.SetNice
        assertEquals(10, restoreNice.expected)
        assertEquals(0, restoreNice.target)
        assertEquals(nice.identity, restoreNice.identity)
    }

    @Test
    fun `planner distinguishes process main thread from Unity main by typed rule`() {
        val rules = parseRules(
            """[{
              "friendly":"Sample game","packages":["com.example.game"],
              "cpuset":{"main_thread":"7","unity_main":"7","other":"0-3"}
            }]"""
        )
        val result = planner.plan(
            rules,
            snapshot(
                process(
                    pid = 100,
                    threads = listOf(
                        thread(100, "com.example.game"),
                        thread(101, "UnityMain")
                    )
                )
            )
        ) as ThreadPlacementPlanResult.Planned

        val affinities = result.plan.applyMutations.filterIsInstance<ThreadPlacementMutation.SetAffinity>()
        assertEquals(2, affinities.size)
        assertEquals(listOf(100, 101), affinities.map { it.identity.tid })
        assertTrue(affinities.all { it.target.canonical == "7" })
    }

    @Test
    fun `trashy nice must remain a positive bounded demotion`() {
        val rules = parseRules(
            """[{
              "friendly":"Sample game","packages":["com.example.game"],
              "cpuset":{"trashy":["Async"],"other":"0-3"}
            }]"""
        )
        val zero = planner.plan(
            rules,
            snapshot(process(threads = listOf(thread(101, "Async")))),
            ThreadPlacementTuning(trashyNice = 0)
        ) as ThreadPlacementPlanResult.Rejected
        val tooHigh = planner.plan(
            rules,
            snapshot(process(threads = listOf(thread(101, "Async")))),
            ThreadPlacementTuning(trashyNice = 20)
        ) as ThreadPlacementPlanResult.Rejected

        assertTrue(zero.reasons.any { it.code == ThreadPlacementRejectionCode.INVALID_TUNING })
        assertTrue(tooHigh.reasons.any { it.code == ThreadPlacementRejectionCode.INVALID_TUNING })
    }

    private fun rules(availableCores: IntRange = 0..8): ThreadPlacementRuleSet {
        val raw = """
            [{
              "friendly": "Demo game",
              "packages": ["com.example.game"],
              "cpuset": {
                "comm": {
                  "7": ["MainThread"],
                  "4-6": ["Render*", "Worker"],
                  "6": ["RenderThread"]
                },
                "rr": ["Render*"],
                "ni": ["MainThread"],
                "other": "0-3"
              }
            }]
        """.trimIndent()
        return parseRules(raw, availableCores)
    }

    private fun parseRules(raw: String, availableCores: IntRange = 0..8): ThreadPlacementRuleSet =
        (ThreadPlacementJsonParser().parse(raw, availableCores.toSet()) as ThreadPlacementLoad.Loaded).ruleSet

    private fun snapshot(
        vararg processes: RunningProcess,
        availableCores: Set<Int> = (0..8).toSet()
    ) = RunningProcessSnapshot(
        capturedAtElapsedRealtimeMs = 10_000,
        availableCores = availableCores,
        processes = processes.toList()
    )

    private fun process(
        pid: Int = 100,
        startTime: Long = 1_000,
        packageName: String = "com.example.game",
        threads: List<RunningThread> = listOf(thread(pid, "Audio", startTime = startTime + 1))
    ) = RunningProcess(pid, startTime, packageName, threads)

    private fun thread(
        tid: Int,
        name: String,
        startTime: Long = tid.toLong() + 1_000,
        affinity: CpuSet = cpu("0-8"),
        placement: ThreadCpuSetPlacement = placement("0-8"),
        scheduling: ThreadSchedulingState = ThreadSchedulingState(ThreadSchedulingPolicy.OTHER, 0),
        nice: Int = 0
    ) = RunningThread(tid, startTime, name, affinity, placement, scheduling, nice)

    private fun placement(cpus: String) = ThreadCpuSetPlacement("/top-app", cpu(cpus))

    private fun cpu(raw: String, available: Set<Int> = (0..8).toSet()): CpuSet =
        requireNotNull(CpuSet.parse(raw, available))

    private companion object {
        val planner = ThreadPlacementPlanner()
    }
}
