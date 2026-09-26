package com.kingzcheung.xime.model

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadQueueTest {
    @Test fun leavingPageDoesNotCancelDownloadAndReturningReusesIt() = runTest {
        val queue = ModelDownloadQueue(backgroundScope)
        var installs = 0
        val task = queue.start("model") { delay(500); installs++ }
        val page = launch { task.await() }
        runCurrent(); page.cancelAndJoin()
        assertFalse(task.isCancelled)
        assertSame(task, queue.start("model") { fail("duplicate download") })
        advanceTimeBy(600); runCurrent()
        assertEquals(1, installs)
        assertTrue(task.isCompleted)
    }
}
