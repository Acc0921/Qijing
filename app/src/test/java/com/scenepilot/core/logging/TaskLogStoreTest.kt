package com.scenepilot.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLogStoreTest {
    @Test fun `in memory log store remains bounded`() {
        val store = InMemoryTaskLogStore()
        repeat(550) { index -> store.append(TaskLog("task", "stage", index.toString(), true, index.toLong())) }
        assertEquals(500, store.recent(600).size)
        assertEquals("549", store.recent(1).single().message)
    }
}
