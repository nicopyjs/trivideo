package com.trivideo.app

import java.io.File

/**
 * "Pool" de clips para el modo aleatorio: dado el path de una carpeta, la recorre
 * recursivamente (incluyendo subcarpetas) y junta todos los archivos de video.
 * El resultado se cachea en memoria para la ultima carpeta escaneada, asi los swaps
 * de panel y la auto-rotacion no vuelven a tocar el disco.
 *
 * Excepcion: las subcarpetas de categoria (/Favoritos/<code>) se saltan siempre,
 * salvo que la carpeta escaneada SEA una de ellas (flujo "Ver categoria"). Asi, un
 * video ya clasificado deja de aparecer cuando navegas /Favoritos o el almacenamiento.
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
        val rootPath = root.absolutePath
        val categoryDirs = CategoryStore.ALL
            .map { VideoFileOps.categoryDir(it.code).absolutePath }
            .toSet()
        val out = ArrayList<String>()
        root.walkTopDown()
            .onEnter { dir ->
                !dir.isHidden &&
                    (dir.absolutePath == rootPath || dir.absolutePath !in categoryDirs)
            }
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
