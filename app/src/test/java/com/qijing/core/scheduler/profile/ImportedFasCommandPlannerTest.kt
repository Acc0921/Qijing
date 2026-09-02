package com.qijing.core.scheduler.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedFasCommandPlannerTest {
    @Test fun `maps real two-cluster fas frequency order to an explicit app contract`() {
        val metadata = mapOf(
            "freq" to ProfileFeatureValue.ArrayValue(listOf(
                ProfileFeatureValue.StringValue("2438400"),
                ProfileFeatureValue.StringValue("1785600")
            ))
        )
        val binding = ProfileDeviceBinding(
            policyIds = listOf(0, 6),
            availableFrequenciesKHz = mapOf(
                0 to listOf(556800, 1785600),
                6 to listOf(1017600, 2438400)
            )
        )

        val result = ImportedFasCommandPlanner().plan(metadata, "com.example.game", binding)
            as ProfileCommandPlan.Planned

        assertEquals(1, result.commands.size)
        assertEquals("scheduler.profile.app_frequencies.set", result.commands.single().capability)
        assertEquals("2438400", result.commands.single().arguments["performance_khz"])
        assertEquals("1785600", result.commands.single().arguments["efficiency_khz"])
        assertEquals("com.example.game", result.commands.single().arguments["package"])
    }

    @Test fun `rejects unknown fas metadata rather than ignoring it`() {
        val result = ImportedFasCommandPlanner().plan(
            metadata = mapOf("offset" to ProfileFeatureValue.NumberValue("1")),
            packageName = "com.example.game",
            binding = binding()
        ) as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_FAS_METADATA_UNSUPPORTED", result.code)
    }

    @Test fun `rejects fas frequency absent from its target cluster table`() {
        val metadata = mapOf(
            "freq" to ProfileFeatureValue.ArrayValue(listOf(
                ProfileFeatureValue.StringValue("999999"),
                ProfileFeatureValue.StringValue("1785600")
            ))
        )

        val result = ImportedFasCommandPlanner().plan(metadata, "com.example.game", binding())
            as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_FAS_FREQUENCY_UNSUPPORTED", result.code)
    }

    @Test fun `rejects ambiguous fas topology`() {
        val metadata = mapOf(
            "freq" to ProfileFeatureValue.ArrayValue(listOf(
                ProfileFeatureValue.StringValue("2438400"),
                ProfileFeatureValue.StringValue("1785600")
            ))
        )

        val result = ImportedFasCommandPlanner().plan(
            metadata,
            "com.example.game",
            ProfileDeviceBinding(listOf(0, 4, 7), mapOf(0 to listOf(1785600), 4 to listOf(2000000), 7 to listOf(2438400)))
        ) as ProfileCommandPlan.Rejected

        assertEquals("PROFILE_FAS_TOPOLOGY_UNSUPPORTED", result.code)
    }

    private fun binding() = ProfileDeviceBinding(
        listOf(0, 6),
        mapOf(0 to listOf(1785600), 6 to listOf(2438400))
    )
}
