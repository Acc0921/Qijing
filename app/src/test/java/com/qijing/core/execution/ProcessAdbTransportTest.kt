package com.qijing.core.execution

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessAdbTransportTest {
    @Test fun `missing executable fails clearly`() = runBlocking {
        val failure = runCatching { ProcessAdbTransport(File("definitely-missing-adb"), timeoutMs = 100).shell("getprop") }
        assertTrue(failure.isFailure)
    }
}
