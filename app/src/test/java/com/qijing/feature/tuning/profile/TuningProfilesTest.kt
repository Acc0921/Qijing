package com.qijing.feature.tuning.profile

import com.qijing.core.scheduler.SchedulerMode
import com.qijing.core.scheduler.SchedulerProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class TuningProfilesTest {
    @Test fun `four built in modes have stable round trippable references`() {
        SchedulerMode.entries.forEach { mode ->
            val reference = TuningProfileReference.BuiltIn(mode)
            assertEquals(reference, TuningProfileReference.parse(reference.stableId))
        }
        assertEquals(listOf("powersave", "balance", "performance", "fast"), SchedulerMode.entries.map { it.stableId })
    }

    @Test fun `custom reference and targets are strictly validated`() {
        val profile = CustomTuningProfile("daily-1", "日常自定义", governor = "schedutil", swappiness = 60)
        assertEquals(null, profile.validationError())
        assertEquals(profile.reference, TuningProfileReference.parse("custom:daily-1"))
        assertNotNull(CustomTuningProfile("bad id", "Bad", governor = "x;reboot").validationError())
    }

    @Test fun `configuration requires selected custom profile to exist`() {
        val missing = GlobalTuningConfiguration(selected = TuningProfileReference.Custom("missing"))
        assertNotNull(missing.validationError())
    }

    @Test fun `third party provider cannot receive system custom parameters`() {
        val custom = CustomTuningProfile("daily", "日常", governor = "schedutil")
        val configuration = GlobalTuningConfiguration(
            selected = custom.reference,
            provider = SchedulerProviderId.UPERF,
            customProfiles = listOf(custom)
        )
        assertNotNull(configuration.validationError())
    }

    @Test fun `global store rejects stale compare and set`() {
        val store = InMemoryGlobalTuningProfileStore()
        val initial = GlobalTuningConfiguration()
        assertTrue(store.create(initial))
        val performance = initial.copy(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.PERFORMANCE),
            revision = 1L,
            updatedAtMs = 10L
        )
        assertTrue(store.compareAndSet(0L, performance))
        assertFalse(store.compareAndSet(0L, initial.copy(revision = 1L)))
        assertEquals(performance, (store.load() as GlobalTuningLoad.Loaded).configuration)
    }

    @Test fun `global configuration defaults to system provider`() {
        assertEquals(SchedulerProviderId.SYSTEM, GlobalTuningConfiguration().provider)
    }

    @Test fun `verified recovery atomically switches to previous configuration with a new revision`() {
        val store = InMemoryGlobalTuningProfileStore()
        val current = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.PERFORMANCE),
            revision = 3L,
            updatedAtMs = 30L
        )
        assertTrue(store.create(current.copy(revision = 0L)))
        assertTrue(store.compareAndSet(0L, current.copy(revision = 1L)))
        val actualCurrent = (store.load() as GlobalTuningLoad.Loaded).configuration
        val previous = GlobalTuningConfiguration(
            selected = TuningProfileReference.BuiltIn(SchedulerMode.BALANCED),
            provider = SchedulerProviderId.SYSTEM,
            revision = 0L,
            updatedAtMs = 1L
        )

        val restored = store.restoreAfterVerifiedRecovery(previous, updatedAtMs = 99L)!!

        assertEquals(TuningProfileReference.BuiltIn(SchedulerMode.BALANCED), restored.selected)
        assertEquals(actualCurrent.revision + 1L, restored.revision)
        assertEquals(99L, restored.updatedAtMs)
        assertTrue(restored.selectionKnown)
        assertEquals(restored, (store.load() as GlobalTuningLoad.Loaded).configuration)
    }

    @Test fun `legacy recovery without previous configuration persists unknown selection`() {
        val store = InMemoryGlobalTuningProfileStore()
        assertTrue(store.create(GlobalTuningConfiguration()))

        val restored = store.restoreAfterVerifiedRecovery(null, updatedAtMs = 42L)!!

        assertFalse(restored.selectionKnown)
        assertEquals(1L, restored.revision)
        assertEquals(42L, restored.updatedAtMs)
    }

    @Test fun `schema one global configuration migrates as known selection`() {
        val legacy = JSONObject(
            """{
              "schema":1,
              "selected":"builtin:balance",
              "provider":"SYSTEM",
              "revision":2,
              "updated":3,
              "custom":[]
            }""".trimIndent()
        )

        val decoded = GlobalTuningConfigurationCodec.decode(legacy)

        assertTrue(decoded.selectionKnown)
        assertEquals(2L, decoded.revision)
    }
}
