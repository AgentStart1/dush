package com.storyteller_f.dush.agent.band

import org.junit.Test
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class PeripheralHealthToolTest {
    @Test fun readsOnlyTheRequestingThreadsPeripherals() = runBlocking {
        val devices = listOf(PeripheralDevice("mine","thread-a","02:00:00:00:00:01"),PeripheralDevice("other","thread-b","02:00:00:00:00:02"))
        val tool = PeripheralHealthTool(devices = { devices }, snapshots = { emptyMap() })
        assertEquals(listOf("mine"),tool.read("thread-a").map { it.deviceId })
        assertTrue(tool.read("unbound").isEmpty())
    }
    @Test fun rechecksOwnershipAfterRefreshingAndLoadingData() = runBlocking {
        var devices = listOf(PeripheralDevice("device","thread-a","02:00:00:00:00:01"))
        val tool = PeripheralHealthTool(devices = { devices }, snapshots = { devices = devices.map { it.copy(threadId = "thread-b") }; emptyMap() })
        assertTrue(tool.read("thread-a").isEmpty())
    }
}
