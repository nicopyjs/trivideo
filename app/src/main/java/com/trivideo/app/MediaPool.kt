package com.trivideo.app

import java.io.File

/**
 * "Pool" de clips para el modo aleatorio: dado el path de una carpeta, la recorre
 * recursivamente (incluyendo subcarpetas) y junta todos los archivos de video.
 * El resultado se cachea en memoria para la ultima carpeta escaneada, asi los swaps
 * de panel y la auto-rotacion no vuelven a tocar el disco.
 */
object MediaPool {

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv"
    )

    @Volatile
    private var cachedFolder: String? = null

    @Volatile
    private var cachedClips: List<String> = emptyList()

    /** Escaneo bloqueante: llamar siempre desde un hilo de fondo. */
    fun scan(folderPath: String): List<String> {
        val root = File(folderPath)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<String>()
        root.walkTopDown()
            .onEnter { !it.isHidden }
            .forEach { file ->
                if (file.isFile && !file.isHidden &&
                    file.extension.lowercase() in VIDEO_EXTENSIONS
                ) {
                    out.add(file.absolutePath)
                }
            }
        cachedFolder = folderPath
        cachedClips = out
        return out
    }

    fun cachedFor(folderPath: String): List<String> =
        if (cachedFolder == folderPath) cachedClips else emptyList()

    /** Mantiene el cache al dia cuando la lista cambia sin re-escanear (favoritos, categorias). */
    fun replaceCached(folderPath: String, clips: List<String>) {
        if (cachedFolder == folderPath) cachedClips = clips
    }
}
