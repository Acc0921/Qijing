package com.qijing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qijing.core.execution.ProcessSuTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootTransportDeviceTest {
    @Test
    fun authorizedRootTransportReportsUidZero() = runBlocking {
        assumeTrue("仅在存在 /system/bin/su 的授权 Root 设备执行", File("/system/bin/su").canExecute())
        assertEquals("0", ProcessSuTransport(timeoutMs = 5_000L).execute("id -u"))
    }
}
