package com.trivideo.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache

/**
 * Cola LIFO (no FIFO): al pedir una miniatura, se va siempre al frente. Asi, si el usuario
 * scrollea rapido, lo ultimo pedido (lo que esta en pantalla ahora) se procesa antes que
 * pedidos viejos de items por los que ya paso, en vez de esperar en orden de llegada.
 */
object VideoThumbnailLoader {
    private const val THREAD_COUNT = 3

    private val cache = LruCache<String, Bitmap>(60)
    private val pending = ArrayDeque<String>()
    private val callbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var activeThreads = 0

    fun load(path: String, onReady: (Bitmap?) -> Unit) {
        val cached = cache.get(path)
        if (cached != null) {
            onReady(cached)
            return
        }
        synchronized(lock) {
            pending.remove(path)
            pending.addFirst(path)
            callbacks.getOrPut(path) { mutableListOf() }.add(onReady)
            if (activeThreads < THREAD_COUNT) {
                activeThreads++
                Thread { workerLoop() }.start()
            }
        }
    }

    private fun workerLoop() {
        while (true) {
            val path = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    activeThreads--
                    return
                }
                next
            }
            val bitmap = decode(path)
            if (bitmap != null) cache.put(path, bitmap)
            val pathCallbacks = synchronized(lock) { callbacks.remove(path) }
            pathCallbacks?.forEach { callback -> mainHandler.post { callback(bitmap) } }
        }
    }

    private fun decode(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
