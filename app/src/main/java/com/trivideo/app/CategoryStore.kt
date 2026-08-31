package com.trivideo.app

/**
 * Categorias fijas para clasificar clips. Cada categoria es una subcarpeta de
 * /Favoritos (`/Favoritos/<code>`); clasificar un video = moverlo a esa subcarpeta.
 * Por ahora son 3 y estan hardcodeadas (un boton por categoria en cada panel).
 */
data class Category(val code: String, val name: String) {
    /** Etiqueta corta para el boton/badge. */
    val short: String get() = code.uppercase()
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
    )

    fun byCode(code: String?): Category? = ALL.firstOrNull { it.code == code }
}
