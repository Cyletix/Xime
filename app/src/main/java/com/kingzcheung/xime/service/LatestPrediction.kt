package com.kingzcheung.xime.service

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One inference at a time, with only the latest pending context retained. */
internal class LatestPrediction(
    private val scope: CoroutineScope,
    private val predict: suspend (String) -> List<String>,
    private val deliver: (List<String>) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val gate = Mutex()
    private var job: Job? = null
    @Volatile private var generation = 0L
    private val pendingGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val isPending: Boolean get() = pendingGeneration.get() != 0L
    private var nextAllowed = 0L

    @Synchronized fun invalidate(): Boolean {
        generation++
        val pending = pendingGeneration.getAndSet(0L) != 0L
        job?.cancel(); job = null
        return pending
    }

    @Synchronized fun submit(text: String) {
        invalidate()
        val request = generation
        pendingGeneration.set(request)
        job = scope.launch {
            try {
                delay(160) // Let a burst settle before starting expensive native inference.
                gate.withLock {
                    delay((nextAllowed - now()).coerceAtLeast(0))
                    ensureActive()
                    val start = now()
                    try {
                        val result = predict(text)
                        ensureActive()
                        if (request == generation) deliver(result)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    finally {
                        // A non-cancellable Binder call retains the gate until it returns.
                        // Slow devices get a cooldown rather than a growing native queue.
                        val elapsed = now() - start
                        nextAllowed = now() + if (elapsed > 350) elapsed.coerceAtMost(1500) else 0
                    }
                }
            } finally { pendingGeneration.compareAndSet(request, 0L) }
        }
    }
}
