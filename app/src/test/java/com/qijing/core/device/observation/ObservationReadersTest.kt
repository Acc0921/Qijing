package com.qijing.core.device.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationReadersTest {
    @Test
    fun `cpu reports policies per-core frequency and load only after second sample`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/cpu"] = listOf("cpu0", "cpu1", "cpufreq")
            directories["/cpu/cpufreq"] = listOf("policy0")
            texts["/cpu/online"] = "0-1"
            texts["/cpu/cpufreq/policy0/related_cpus"] = "0-1"
            texts["/cpu/cpufreq/policy0/scaling_cur_freq"] = "1500000"
            texts["/cpu/cpufreq/policy0/cpuinfo_min_freq"] = "300000"
            texts["/cpu/cpufreq/policy0/cpuinfo_max_freq"] = "2200000"
            texts["/cpu/cpufreq/policy0/scaling_min_freq"] = "500000"
            texts["/cpu/cpufreq/policy0/scaling_max_freq"] = "2000000"
            texts["/cpu/cpufreq/policy0/scaling_governor"] = "schedutil"
            texts["/cpu/cpufreq/policy0/scaling_available_governors"] = "powersave schedutil performance"
            texts["/cpu/cpu0/cpufreq/cpuinfo_cur_freq"] = "1600000"
            texts["/proc/stat"] = "cpu0 100 0 100 800 0 0 0 0\ncpu1 100 0 100 800 0 0 0 0\n"
        }
        var now = 1_000L
        val reader = CpuObservationReader(fs, "/cpu", "/proc/stat") { now }

        val first = reader.read()

        assertEquals(1, first.policyCount.value)
        assertEquals(setOf(0, 1), first.policies.single().relatedCores)
        assertEquals(MetricStatus.SAMPLING, first.cores[0].loadPercent.status)
        assertEquals(1_600_000L, first.cores[0].currentFrequencyKHz.value)
        assertEquals(1_500_000L, first.cores[1].currentFrequencyKHz.value)
        assertTrue(first.cores[1].currentFrequencyKHz.detail!!.contains("policy0"))

        fs.texts["/proc/stat"] = "cpu0 140 0 130 830 0 0 0 0\ncpu1 110 0 110 880 0 0 0 0\n"
        now = 2_000L
        val second = reader.read()

        assertEquals(70.0, second.cores[0].loadPercent.value!!, 0.001)
        assertEquals(20.0, second.cores[1].loadPercent.value!!, 0.001)
        assertTrue(second.cores.all { it.online.value == true })
    }

    @Test
    fun `cpu keeps proc permission denial distinct from zero load`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/cpu"] = listOf("cpu0")
            directories["/cpu/cpufreq"] = emptyList()
            texts["/cpu/online"] = "0"
            deniedFiles += "/proc/stat"
        }

        val result = CpuObservationReader(fs, "/cpu", "/proc/stat") { 1L }.read()

        assertEquals(MetricStatus.PERMISSION_DENIED, result.cores.single().loadPercent.status)
        assertEquals(null, result.cores.single().loadPercent.value)
    }

    @Test
    fun `zero cpu frequency is inactive instead of a valid zero megahertz reading`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/cpu"] = listOf("cpu0", "cpufreq")
            directories["/cpu/cpufreq"] = listOf("policy0")
            texts["/cpu/online"] = "0"
            texts["/cpu/cpufreq/policy0/related_cpus"] = "0"
            texts["/cpu/cpufreq/policy0/scaling_cur_freq"] = "0"
            texts["/cpu/cpu0/cpufreq/cpuinfo_cur_freq"] = "0"
            texts["/proc/stat"] = "cpu0 1 0 1 8"
        }

        val result = CpuObservationReader(fs, "/cpu", "/proc/stat") { 1L }.read()

        assertEquals(MetricStatus.INACTIVE, result.policies.single().currentFrequencyKHz.status)
        assertEquals(MetricStatus.INACTIVE, result.cores.single().currentFrequencyKHz.status)
        assertEquals(null, result.cores.single().currentFrequencyKHz.value)
    }

    @Test
    fun `memory parses ram swap and every zram device including selected algorithm`() {
        val fs = FakeObservationFileSystem().apply {
            texts["/proc/meminfo"] = """
                MemTotal:       1000 kB
                MemAvailable:    400 kB
                Cached:          200 kB
                SwapTotal:       800 kB
                SwapFree:        300 kB
            """.trimIndent()
            texts["/proc/sys/vm/swappiness"] = "80"
            directories["/sys/block"] = listOf("sda", "zram1", "zram0")
            texts["/sys/block/zram0/disksize"] = "1048576"
            texts["/sys/block/zram0/mm_stat"] = "600000 300000 350000 0 0 2 3"
            texts["/sys/block/zram0/comp_algorithm"] = "lzo [lz4] zstd"
            texts["/sys/block/zram1/disksize"] = "0"
            texts["/sys/block/zram1/mm_stat"] = "0 0 0 0 0 0 0"
            texts["/sys/block/zram1/comp_algorithm"] = "[lzo] lz4"
        }

        val result = MemoryObservationReader(fs, "/proc/meminfo", "/proc/sys/vm/swappiness", "/sys/block") { 10L }.read()

        assertEquals(1_024_000L, result.totalBytes.value)
        assertEquals(512_000L, result.swapUsedBytes.value)
        assertEquals(2, result.zramDeviceCount.value)
        assertEquals(listOf("zram0", "zram1"), result.zramDevices.map { it.device })
        assertEquals("lz4", result.zramDevices[0].currentAlgorithm.value)
        assertEquals(setOf("lzo", "lz4", "zstd"), result.zramDevices[0].algorithms.value)
        assertEquals(MetricStatus.INACTIVE, result.zramDevices[1].active.status)
        assertFalse(result.zramDevices[1].active.value!!)
    }

    @Test
    fun `gpu uses kgsl adapter and preserves unavailable node status`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/kgsl"] = emptyList()
            texts["/kgsl/devfreq/cur_freq"] = "600000000"
            texts["/kgsl/devfreq/min_freq"] = "200000000"
            texts["/kgsl/devfreq/max_freq"] = "900000000"
            texts["/kgsl/devfreq/available_frequencies"] = "200000000 600000000 900000000"
            texts["/kgsl/devfreq/governor"] = "msm-adreno-tz"
            texts["/kgsl/gpubusy"] = "25 100"
        }

        val result = GpuObservationReader(fs, "/kgsl", "/devfreq") { 20L }.read()

        assertEquals(MetricStatus.AVAILABLE, result.status)
        val gpu = result.devices.single()
        assertEquals("qualcomm-kgsl", gpu.adapter)
        assertEquals(600_000_000L, gpu.currentFrequencyHz.value)
        assertEquals(25.0, gpu.loadPercent.value!!, 0.001)
        assertTrue(gpu.loadPercent.estimated)
    }

    @Test
    fun `gpu generic devfreq adapter reads mali utilization`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/devfreq"] = listOf("soc:qcom,gpubw", "13000000.mali")
            texts["/devfreq/13000000.mali/cur_freq"] = "500000000"
            texts["/devfreq/13000000.mali/min_freq"] = "100000000"
            texts["/devfreq/13000000.mali/max_freq"] = "800000000"
            texts["/devfreq/13000000.mali/available_frequencies"] = "100000000 500000000 800000000"
            texts["/devfreq/13000000.mali/governor"] = "simple_ondemand"
            texts["/devfreq/13000000.mali/utilization"] = "47%"
        }

        val result = GpuObservationReader(fs, "/kgsl", "/devfreq") { 30L }.read()

        assertEquals("mali-devfreq", result.devices.single().adapter)
        assertEquals(47.0, result.devices.single().loadPercent.value!!, 0.001)
    }

    @Test
    fun `battery prefers public api and labels calculated power as estimate`() {
        val platform = BatteryPlatformSource {
            BatteryPlatformSnapshot(
                capacityPercent = PlatformBatteryValue.Available(73),
                currentMicroAmps = PlatformBatteryValue.Available(-500_000L),
                voltageMilliVolts = PlatformBatteryValue.Available(4_000L),
                temperatureCelsius = PlatformBatteryValue.Available(32.5),
                flow = PlatformBatteryValue.Available(BatteryFlow.DISCHARGING)
            )
        }

        val result = BatteryObservationReader(platform, FakeObservationFileSystem(), "/power") { 40L }.read()

        assertEquals(MetricSource.ANDROID_API, result.currentMicroAmps.source)
        assertEquals(2_000.0, result.powerMilliWatts.value!!, 0.001)
        assertTrue(result.powerMilliWatts.estimated)
        assertEquals(BatteryFlow.DISCHARGING, result.flow.value)
    }

    @Test
    fun `battery discovers type instead of assuming directory name and uses direct power`() {
        val fs = FakeObservationFileSystem().apply {
            directories["/power"] = listOf("usb", "bms-main")
            texts["/power/usb/type"] = "USB"
            texts["/power/bms-main/type"] = "Battery"
            texts["/power/bms-main/capacity"] = "90"
            texts["/power/bms-main/current_now"] = "1000000"
            texts["/power/bms-main/voltage_now"] = "4200000"
            texts["/power/bms-main/temp"] = "315"
            texts["/power/bms-main/power_now"] = "4100000"
            texts["/power/bms-main/status"] = "Charging"
        }

        val failingPlatform = BatteryPlatformSource { error("binder unavailable") }
        val result = BatteryObservationReader(failingPlatform, fs, "/power") { 50L }.read()

        assertEquals(4_100.0, result.powerMilliWatts.value!!, 0.001)
        assertFalse(result.powerMilliWatts.estimated)
        assertEquals(4_200L, result.voltageMilliVolts.value)
        assertEquals(31.5, result.temperatureCelsius.value!!, 0.001)
    }

    @Test
    fun `battery sysfs permission denial remains explicit`() {
        val fs = FakeObservationFileSystem().apply { deniedDirectories += "/power" }

        val result = BatteryObservationReader(null, fs, "/power") { 60L }.read()

        assertEquals(MetricStatus.PERMISSION_DENIED, result.currentMicroAmps.status)
        assertEquals(MetricStatus.PERMISSION_DENIED, result.voltageMilliVolts.status)
        assertEquals(null, result.powerMilliWatts.value)
    }

    private class FakeObservationFileSystem : ObservationFileSystem {
        val texts = mutableMapOf<String, String>()
        val directories = mutableMapOf<String, List<String>>()
        val deniedFiles = mutableSetOf<String>()
        val deniedDirectories = mutableSetOf<String>()

        override fun readText(path: String): FileReadResult = when {
            path in deniedFiles -> FileReadResult.PermissionDenied
            path in texts -> FileReadResult.Success(texts.getValue(path))
            else -> FileReadResult.Missing
        }

        override fun list(path: String): DirectoryReadResult = when {
            path in deniedDirectories -> DirectoryReadResult.PermissionDenied
            path in directories -> DirectoryReadResult.Success(directories.getValue(path))
            else -> DirectoryReadResult.Missing
        }
    }
}
