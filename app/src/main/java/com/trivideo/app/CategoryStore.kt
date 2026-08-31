package com.trivideo.app

/**
 * Categorias fijas para clasificar clips. Cada categoria es una subcarpeta de
 * /Favoritos (`/Favoritos/<code>`); clasificar un video = moverlo a esa subcarpeta.
 *
 * `code` es el nombre de carpeta en disco (estable, no cambiarlo). `label` es solo
 * el texto del boton/chip: puede ser mas largo que el code cuando ayuda a leerlo
 * (ej. `tp` -> "TOP"). Se mantiene corto y neutro porque el repo es publico.
 */
data class Category(val code: String, val label: String) {
    /** Alias historico: varios call-sites usaban `short`/`name`. */
    val short: String get() = label
    val name: String get() = label
}

object CategoryStore {

    /** Token interno para "sin categoria" en los filtros (no es un code valido). */
    const val UNCATEGORIZED = " none"

    val ALL: List<Category> = listOf(
        Category("an", "AN"),
        Category("tt", "TT"),
        Category("cs", "CS"),
        Category("cu", "CU"),
        Category("or", "OR"),
        Category("vg", "VG"),
        Category("cp", "COMP"),
        Category("tp", "TOP"),
    )

    fun byCode(code: String?): Category? = ALL.firstOrNull { it.code == code }
}
