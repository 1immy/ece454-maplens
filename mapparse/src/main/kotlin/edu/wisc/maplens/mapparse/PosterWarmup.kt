package edu.wisc.maplens.mapparse

import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.opencv.android.OpenCVLoader

/**
 * Loads what a Fast-mode parse needs before the photo is taken: OpenCV, ML Kit's text model and
 * the recognizer networks. Loading them inside the parse cost about half a second of it (Pixel
 * 7a, Nov 14); the capture screen is open for longer than that before anyone presses the
 * shutter. A parse that starts before the warm-up ends waits for the networks being loaded
 * rather than loading its own; one with no warm-up at all loads and frees them itself, as
 * before. The parse's result is the same either way.
 */
object PosterWarmup {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "poster-warmup").apply { isDaemon = true }
    }
    private var pending: Future<*>? = null

    /** Call when the capture screen opens. Returns at once. */
    @Synchronized
    fun start() {
        if (Recognizers.kept) return
        Recognizers.kept = true
        pending = worker.submit {
            // A failed warm-up only means the parse loads what it needs itself.
            runCatching {
                if (!OpenCVLoader.initLocal()) return@runCatching
                PosterMlKit.warm()
                Recognizers.warm()
            }
        }
    }

    /** Call when the capture screen closes or its review is shown. Frees the networks. */
    @Synchronized
    fun release() {
        if (!Recognizers.kept) return
        Recognizers.kept = false
        pending?.cancel(true)
        pending = null
        worker.execute { Recognizers.release() }
    }

    /** Blocks until a started warm-up is done: for timing a warm parse in the probe. */
    internal fun await() {
        val running = synchronized(this) { pending }
        runCatching { running?.get() }
    }
}
