package com.qijing.feature.tuning.profile

import com.qijing.core.device.observation.CpuObservation
import com.qijing.core.device.observation.CpuPolicyObservation
import com.qijing.core.device.observation.MetricSource
import com.qijing.core.device.observation.ObservedMetric
import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalTuningResolverTest {
    @Test fun `extreme mode resolves each policy independently`() {
        val cpu = CpuObservation(
            available(2),
            listOf(policy("policy0", setOf(0, 1), 1_800_000), policy("policy4", setOf(4, 5), 3_000_000)),
            emptyList(),
            1L
        )
        val config = GlobalTuningConfiguration(selected = TuningProfileReference.BuiltIn(SchedulerMode.EXTREME))

        val target = (GlobalTuningResolver().resolve(config, cpu) as GlobalTuningResolution.Ready).target

        assertEquals(listOf(1_800_000L, 3_000_000L), target.cpu.policies.map { it.maxFrequencyKHz })
        assertTrue(target.cpu.policies.all { it.governor == "performance" })
        assertTrue(target.warnings.isNotEmpty())
    }

    @Test fun `third party mode never produces native writes`() {
        val config = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.BALANCED),
            provider = SchedulerProviderId.UPERF
        )

        val target = (GlobalTuningResolver().resolve(config, CpuObservation(available(0), emptyList(), emptyList(), 1L)) as GlobalTuningResolution.Ready).target

        assertEquals(SchedulerProviderId.UPERF, target.provider)
        assertTrue(target.cpu.policies.isEmpty())
        assertEquals(SchedulerMode.BALANCED, target.mode)
    }

    private fun policy(id: String, cores: Set<Int>, max: Long) = CpuPolicyObservation(
        id, cores, available(max / 2), available(300_000), available(max), available(300_000), available(max),
        available("schedutil"), available(setOf("powersave", "schedutil", "performance"))
    )

    private fun <T> available(value: T) = ObservedMetric.available(value, MetricSource.SYSFS, 1L)
}
