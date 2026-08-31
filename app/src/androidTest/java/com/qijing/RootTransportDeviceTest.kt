package com.qijing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qijing.core.execution.ProcessSuTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootTransportDeviceTest {
    @Test
    fun authorizedRootTransportReportsUidZero() = runBlocking {
        assertEquals("0", ProcessSuTransport(timeoutMs = 5_000L).execute("id -u"))
    }
}
