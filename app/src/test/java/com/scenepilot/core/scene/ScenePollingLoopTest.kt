package com.scenepilot.core.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class ScenePollingLoopTest {
    @Test fun `poll emits only when package changes`() {
        var packageName = "com.demo.one"
        val emitted = mutableListOf<String>()
        val loop = ScenePollingLoop({ packageName }, intervalMs = 500, onPackageChanged = emitted::add)
        loop.pollNow(); loop.pollNow(); packageName = "com.demo.two"; loop.pollNow()
        assertEquals(listOf("com.demo.one", "com.demo.two"), emitted)
    }
}
