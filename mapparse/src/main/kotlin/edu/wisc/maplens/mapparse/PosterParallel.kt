package edu.wisc.maplens.mapparse

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Worker threads for parse stages that split into independent pieces. */
internal val parseThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

/**
 * [transform] applied to every element on up to [parseThreads] threads, results in order.
 * The same work as [map], only spread out, so the result is identical. Cancelling the calling
 * thread (an interrupt, as [checkCancellation] expects) stops the workers too.
 */
internal fun <T, R> List<T>.parallelMap(transform: (T) -> R): List<R> {
    if (size < 2 || parseThreads == 1) return map(transform)
    val pool = Executors.newFixedThreadPool(minOf(size, parseThreads))
    try {
        return map { item -> pool.submit(Callable { transform(item) }) }.map { it.await() }
    } finally {
        pool.shutdownNow()
    }
}

/**
 * Runs [task] on its own thread while the caller does other work. [Background.await] returns
 * its result or rethrows its failure; cancelling the caller while waiting cancels the task.
 */
internal class Background<R>(task: () -> R) {
    private val pool: ExecutorService = Executors.newSingleThreadExecutor()
    private val future: Future<R> = pool.submit(Callable(task))

    init {
        pool.shutdown()
    }

    fun await(): R = future.await()

    /** Stops the task, e.g. when the caller failed before it needed the result. */
    fun cancel() {
        future.cancel(true)
    }
}

private fun <R> Future<R>.await(): R = try {
    get()
} catch (failure: ExecutionException) {
    throw failure.cause ?: failure
} catch (_: InterruptedException) {
    cancel(true)
    Thread.currentThread().interrupt()
    throw CancellationException("Poster scan cancelled")
}
