package com.trivideo.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Operaciones de organizacion de archivos de video: renombrar y mover a /Favoritos
 * en la raiz del almacenamiento. Requiere MANAGE_EXTERNAL_STORAGE (ya declarado).
 * Despues de cada operacion reescribe las referencias en los sets guardados y en
 * las ranuras del reproductor para que nada quede apuntando al path viejo.
 */
object VideoFileOps {

    private const val PREFS_NAME = "trivideo_prefs"
    const val FAVORITES_DIR_NAME = "Favoritos"

    fun favoritesDir(): File = File(Environment.getExternalStorageDirectory(), FAVORITES_DIR_NAME)

    fun isInFavorites(path: String): Boolean {
        val parent = File(path).parentFile ?: return false
        return parent.absolutePath == favoritesDir().absolutePath
    }

    fun sanitizeBaseName(raw: String): String =
        raw.trim().replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").take(120)

    /** Renombra manteniendo extension y carpeta. Devuelve el nuevo File, o null si falla. */
    fun rename(context: Context, path: String, newBaseName: String): File? {
        val src = File(path)
        if (!src.exists()) return null
        val base = sanitizeBaseName(newBaseName)
        if (base.isEmpty()) return null
        val ext = src.extension
        val targetName = if (ext.isEmpty()) base else "$base.$ext"
        val candidate = File(src.parentFile, targetName)
        if (candidate.absolutePath == src.absolutePath) return src
        val target = uniqueTarget(candidate)
        return if (src.renameTo(target)) {
            scan(context, src.absolutePath, target.absolutePath)
            target
        } else {
            null
        }
    }

    /** Mueve el archivo a /Favoritos (la crea si no existe). Devuelve el nuevo File o null. */
    fun moveToFavorites(context: Context, path: String): File? {
        val src = File(path)
        if (!src.exists()) return null
        if (isInFavorites(path)) return src
        val dir = favoritesDir()
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = uniqueTarget(File(dir, src.name))
        val ok = src.renameTo(target) || copyThenDelete(src, target)
        return if (ok) {
            scan(context, src.absolutePath, target.absolutePath)
            target
        } else {
            null
        }
    }

    private fun uniqueTarget(target: File): File {
        if (!target.exists()) return target
        val dir = target.parentFile
        val name = target.nameWithoutExtension
        val ext = target.extension
        var i = 2
        while (true) {
            val candidate = File(dir, if (ext.isEmpty()) "$name ($i)" else "$name ($i).$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun copyThenDelete(src: File, target: File): Boolean = try {
        src.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (src.length() == target.length()) {
            src.delete()
            true
        } else {
            target.delete()
            false
        }
    } catch (_: Exception) {
        runCatching { target.delete() }
        false
    }

    private fun scan(context: Context, vararg paths: String) {
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
        }
    }

    /** Reescribe oldPath -> newFile en todos los sets guardados y en las ranuras uri_0..3. */
    fun updateReferences(context: Context, oldPath: String, newFile: File) {
        val oldUri = Uri.fromFile(File(oldPath)).toString()
        val newUri = Uri.fromFile(newFile).toString()

        val sets = VideoSetsStore.load(context)
        var changed = false
        val updated = sets.map { set ->
            if (set.uris.any { it == oldUri || it == oldPath }) {
                changed = true
                set.copy(uris = set.uris.map { if (it == oldUri || it == oldPath) newUri else it })
            } else {
                set
            }
        }
        if (changed) VideoSetsStore.save(context, updated)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var prefsChanged = false
        for (i in 0 until 4) {
            val stored = prefs.getString("uri_$i", null) ?: continue
            val storedPath = Uri.parse(stored).path
            if (stored == oldUri || storedPath == oldPath) {
                editor.putString("uri_$i", newUri)
                editor.remove("pos_$i")
                prefsChanged = true
            }
        }
        if (prefsChanged) editor.apply()
    }
}
