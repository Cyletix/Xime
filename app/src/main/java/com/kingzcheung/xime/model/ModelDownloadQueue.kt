package com.kingzcheung.xime.model

import kotlinx.coroutines.*

/** Owned by the application; cancelling a page's await does not cancel its download. */
internal class ModelDownloadQueue(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Deferred<Unit>>()
    @Synchronized fun start(id: String, download: suspend () -> Unit): Deferred<Unit> {
        jobs[id]?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) { download() }
        jobs[id] = job
        job.invokeOnCompletion { synchronized(this) { if (jobs[id] === job) jobs.remove(id) } }
        job.start()
        return job
    }
}
