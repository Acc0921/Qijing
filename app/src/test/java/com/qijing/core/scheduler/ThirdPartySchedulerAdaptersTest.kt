package com.qijing.core.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartySchedulerAdaptersTest {
    @Test fun `uperf rejects mismatched module identity and creates no plan`() {
        val reader = FakeReader().apply {
            file(FixedSchedulerPath.UPERF_MODULE_PROP, "id=someone_else\nname=Uperf\nversion=1")
            file(FixedSchedulerPath.UPERF_MODE_SWITCH, "ignored")
        }
        val adapter = UperfSchedulerAdapter(reader)

        assertEquals(SchedulerAvailability.IDENTITY_REJECTED, adapter.probe().availability)
        assertTrue(adapter.planMode(SchedulerMode.BALANCED) is SchedulerPlanResult.Unavailable)
    }

    @Test fun `uperf exposes only a typed fixed operation plan`() {
        val reader = FakeReader().apply {
            file(FixedSchedulerPath.UPERF_MODULE_PROP, "id=uperf\nname=Uperf\nversion=3")
            file(FixedSchedulerPath.UPERF_MODE_SWITCH, "not executed")
            file(FixedSchedulerPath.UPERF_MODE_STATE, "balance\n")
        }
        val adapter = UperfSchedulerAdapter(reader)

        val probe = adapter.probe()
        val planned = adapter.planMode(SchedulerMode.EXTREME) as SchedulerPlanResult.Planned
        assertEquals(SchedulerAvailability.READY, probe.availability)
        assertEquals(SchedulerMode.BALANCED, probe.activeMode)
        assertEquals(SchedulerOperation.UPERF_MODE_SWITCH, planned.plan.operation)
        assertEquals(SchedulerMode.EXTREME, planned.plan.mode)
    }

    @Test fun `fas rs reads only its fixed mode node and returns typed plan`() {
        val reader = FakeReader().apply {
            file(FixedSchedulerPath.FAS_RS_MODULE_PROP, "id=fas-rs\nname=fas-rs\nversion=1.0")
            file(FixedSchedulerPath.FAS_RS_MODE_NODE, "performance")
        }
        val adapter = FasRsSchedulerAdapter(reader)

        assertEquals(SchedulerMode.PERFORMANCE, adapter.probe().activeMode)
        assertTrue(adapter.planMode(SchedulerMode.POWER_SAVE) is SchedulerPlanResult.Planned)
        assertEquals(
            setOf(FixedSchedulerPath.FAS_RS_MODULE_PROP, FixedSchedulerPath.FAS_RS_MODE_NODE),
            reader.requestedPaths
        )
    }

    @Test fun `uperf gt uses the fixed compatible powercfg contract`() {
        val reader = FakeReader().apply {
            file(FixedSchedulerPath.UPERF_GT_MODULE_PROP, "id=uperf\nname=Uperf Game Turbo\nversion=2\nASOPT_VERSIONCODE=260")
            file(FixedSchedulerPath.UPERF_MODE_SWITCH, "not executed")
            file(FixedSchedulerPath.UPERF_MODE_STATE, "performance")
        }
        val adapter = UperfGtSchedulerAdapter(reader)

        assertEquals(SchedulerAvailability.READY, adapter.probe().availability)
        assertTrue(SchedulerCapability.MODE_PLAN in adapter.probe().capabilities)
        assertTrue(adapter.planMode(SchedulerMode.PERFORMANCE) is SchedulerPlanResult.Planned)
    }

    @Test fun `configuration module identity alone is never executable`() {
        val reader = FakeReader().apply {
            file(
                FixedSchedulerPath.CONFIG_BRIDGE_MODULE_PROP,
                "id=Scene_Config_replace\nname=若晴· Scene二改调度线程\nversion=7月10日"
            )
        }
        val adapter = ConfigBridgeSchedulerAdapter(reader)

        val probe = adapter.probe()
        assertEquals(SchedulerAvailability.DETECTED, probe.availability)
        assertEquals(setOf(SchedulerCapability.IDENTITY_READ), probe.capabilities)
        assertTrue(adapter.planMode(SchedulerMode.BALANCED) is SchedulerPlanResult.Unavailable)
    }

    @Test fun `configuration bridge requires token state and fixed switch before planning`() {
        val reader = FakeReader().apply {
            file(
                FixedSchedulerPath.CONFIG_BRIDGE_MODULE_PROP,
                "id=Scene_Config_replace\nname=若晴· Scene二改调度线程\nversion=7月10日"
            )
            file(FixedSchedulerPath.CONFIG_BRIDGE_CONTRACT, "qijing-scheduler-bridge-v1\n")
            file(FixedSchedulerPath.CONFIG_BRIDGE_MODE_STATE, "performance\n")
            file(FixedSchedulerPath.CONFIG_BRIDGE_MODE_SWITCH, "not executed by probe")
        }
        val adapter = ConfigBridgeSchedulerAdapter(reader)

        val probe = adapter.probe()
        val plan = adapter.planMode(SchedulerMode.POWER_SAVE) as SchedulerPlanResult.Planned
        assertEquals(SchedulerAvailability.READY, probe.availability)
        assertEquals(SchedulerMode.PERFORMANCE, probe.activeMode)
        assertEquals(SchedulerOperation.CONFIG_BRIDGE_MODE_SWITCH, plan.plan.operation)
    }

    private class FakeReader : FixedSchedulerPathReader {
        private val values = mutableMapOf<FixedSchedulerPath, String>()
        val requestedPaths = linkedSetOf<FixedSchedulerPath>()

        fun file(path: FixedSchedulerPath, value: String) { values[path] = value }

        override fun status(path: FixedSchedulerPath): FixedPathStatus {
            requestedPaths += path
            return FixedPathStatus(path, path in values, path.absolutePath, directory = false, readable = path in values)
        }

        override fun readUtf8(path: FixedSchedulerPath, maxBytes: Int): String? {
            requestedPaths += path
            return values[path]?.takeIf { it.toByteArray().size <= maxBytes }
        }
    }
}
