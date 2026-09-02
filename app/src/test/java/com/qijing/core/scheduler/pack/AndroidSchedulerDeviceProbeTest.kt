package com.qijing.core.scheduler.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSchedulerDeviceProbeTest {
    @Test fun `uses board codename before generic hardware vendor`() {
        assertEquals("sun", AndroidSchedulerDeviceProbe.preferredPlatform("sun", "qcom"))
    }

    @Test fun `falls back to hardware when board is unavailable`() {
        assertEquals("taro", AndroidSchedulerDeviceProbe.preferredPlatform(" ", "taro"))
        assertNull(AndroidSchedulerDeviceProbe.preferredPlatform("", ""))
    }
}
