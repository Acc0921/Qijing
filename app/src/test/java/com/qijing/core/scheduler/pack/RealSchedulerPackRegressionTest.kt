package com.qijing.core.scheduler.pack

import com.qijing.core.scheduler.ThreadPlacementLoad
import com.qijing.core.scheduler.ThreadPlacementJsonParser
import com.qijing.core.scheduler.profile.ProfileCompileResult
import com.qijing.core.scheduler.profile.ProfileCompiler
import com.qijing.core.scheduler.profile.ProfileCommandPlan
import com.qijing.core.scheduler.profile.ProfileCommandPlanner
import com.qijing.core.scheduler.profile.ProfileDeviceBinding
import com.qijing.core.scheduler.profile.ProfilePhase
import com.qijing.core.scheduler.profile.ProfileRoute
import com.qijing.core.scheduler.profile.GestureBoostContractPlan
import com.qijing.core.scheduler.profile.GestureBoostContractPlanner
import com.qijing.core.execution.PrivilegedWriteCommandMapper
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in regression for an actual user-supplied pack. The fixture stays outside Git; CI keeps the
 * deterministic synthetic tests while local acceptance can exercise every retained variant with:
 * `QIJING_SCHEDULER_PACK=<absolute zip path>`.
 */
class RealSchedulerPackRegressionTest {
    @Test fun `every real pack variant compiles profile and thread rules`() {
        val path = System.getenv(ENVIRONMENT).orEmpty()
        assumeTrue("Set $ENVIRONMENT to run the real-pack regression", path.isNotBlank())
        val archive = File(path)
        assertTrue("Scheduler pack does not exist: $path", archive.isFile)

        val imported = archive.inputStream().use { SchedulerPackImporter().import(it) }
        assertTrue(
            "Pack import failed: $imported",
            imported is SchedulerPackImportResult.Imported
        )
        val pack = (imported as SchedulerPackImportResult.Imported).pack
        assertTrue("The real pack contains no variants", pack.variants.isNotEmpty())

        val failures = pack.variants.flatMap { variant ->
            buildList {
                val profile = ProfileCompiler().compile(variant.profileJson, variant.imports)
                if (profile is ProfileCompileResult.Rejected) {
                    add("${variant.relativePath}/profile.json: ${profile.reason}")
                } else if (profile is ProfileCompileResult.Compiled) {
                    val policyIds = variant.hardware.topology.clusterCoreCounts.runningFold(0, Int::plus).dropLast(1)
                    val allConfigurationText = sequenceOf(variant.profileJson) + variant.imports.values.asSequence()
                    val frequencies = Regex("\\b[1-9][0-9]{5,7}\\b").findAll(allConfigurationText.joinToString("\n"))
                        .map { it.value.toLong() }.filter { it in 100_000L..10_000_000L }.toSet().sorted()
                    val binding = ProfileDeviceBinding(policyIds, policyIds.associateWith { frequencies })
                    val planner = ProfileCommandPlanner()
                    profile.program.routes.forEach { (route, operations) ->
                        val plan = planner.plan(profile.program, profile.program.reset + operations, binding)
                        when (plan) {
                            is ProfileCommandPlan.Rejected -> add("${variant.relativePath}/$route: ${plan.code} ${plan.reason}")
                            is ProfileCommandPlan.Planned -> plan.commands.forEach { command ->
                                if (PrivilegedWriteCommandMapper.map(command) !is PrivilegedWriteCommandMapper.Result.Command) {
                                    add("${variant.relativePath}/$route: unmapped ${command.capability}")
                                }
                            }
                        }
                    }
                    profile.program.applicationRules.forEach { rule ->
                        rule.modes.forEach { (mode, imported) ->
                            ProfilePhase.entries.forEach { phase ->
                                val route = ProfileRoute(mode, rule.workload, phase)
                                val operations = profile.program.reset + profile.program.routes[route].orEmpty() +
                                    imported.call + if (phase == ProfilePhase.ACTIVE) imported.active else imported.inactive
                                val plan = planner.plan(profile.program, operations, binding)
                                if (plan is ProfileCommandPlan.Rejected) {
                                    add("${variant.relativePath}/${rule.friendlyName}/$mode/$phase: ${plan.code} ${plan.reason}")
                                } else (plan as ProfileCommandPlan.Planned).commands.forEach { command ->
                                    if (PrivilegedWriteCommandMapper.map(command) !is PrivilegedWriteCommandMapper.Result.Command) {
                                        add("${variant.relativePath}/${rule.friendlyName}/$mode/$phase: unmapped ${command.capability}")
                                    }
                                }
                            }
                        }
                    }
                    when (val gesture = GestureBoostContractPlanner(planner).plan(profile.program, binding)) {
                        is GestureBoostContractPlan.Rejected -> add("${variant.relativePath}/gesture: ${gesture.code} ${gesture.reason}")
                        is GestureBoostContractPlan.Configured -> if (
                            PrivilegedWriteCommandMapper.map(gesture.contract.configureCommand) !is PrivilegedWriteCommandMapper.Result.Command
                        ) add("${variant.relativePath}/gesture: configure command unmapped")
                        else -> Unit
                    }
                }
                val threads = ThreadPlacementJsonParser().parse(
                    variant.threadsJson,
                    variant.hardware.requiredCpuCores
                )
                if (threads is ThreadPlacementLoad.Rejected) {
                    add("${variant.relativePath}/threads.json: ${threads.reason}")
                }
            }
        }
        assertTrue("Real scheduler pack regressions:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private companion object { const val ENVIRONMENT = "QIJING_SCHEDULER_PACK" }
}
