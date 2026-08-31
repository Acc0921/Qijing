package com.qijing.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessEvaluatorTest {
    @Test fun `all checks ready when prerequisites are present`() {
        val report = ReadinessEvaluator().evaluate(true, true, setOf("cpu.read"), true)
        assertTrue(report.ready)
    }

    @Test fun `report explains missing permission`() {
        val report = ReadinessEvaluator().evaluate(false, true, setOf("cpu.read"), true)
        assertFalse(report.ready)
        assertTrue(report.checks.first { it.id == "usage" }.detail.contains("Usage Stats"))
    }
}
