package com.tadmor.app.gl

import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge for passing render parameters from the main thread
 * (Compose / ViewModel) to the GL thread (Renderer).
 *
 * Lock-free via AtomicReference. The generic [T] should be an immutable
 * data class containing all parameters the renderer needs for a frame.
 */
class GLBridge<T>(initial: T) {

    private val ref = AtomicReference(initial)

    /** Called from the main thread to update parameters. */
    fun post(value: T) {
        ref.set(value)
    }

    /** Called from the GL thread each frame to read the latest parameters. */
    fun consume(): T = ref.get()
}
