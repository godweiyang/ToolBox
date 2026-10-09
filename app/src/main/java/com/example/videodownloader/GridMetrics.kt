package com.example.videodownloader

/** Pure layout policy for the 2-5 column home grid. */
data class GridPresentation(
    val columns: Int,
    val iconDp: Int,
    val iconPaddingDp: Int,
    val outerSpacingDp: Int,
    val verticalPaddingDp: Int,
    val titleSp: Float,
    val titleMaxLines: Int,
    val descriptionMaxLines: Int
) {
    val showDescription: Boolean get() = descriptionMaxLines > 0
}

object GridMetrics {
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 5
    const val DEFAULT_COLUMNS = 2
    const val WIDE_SCREEN_DP = 600

    fun normalizeColumns(value: Int): Int = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    fun defaultColumnsForWidth(screenWidthDp: Int): Int =
        if (screenWidthDp >= WIDE_SCREEN_DP) 3 else DEFAULT_COLUMNS

    /** Kept for existing callers/tests; user preference should override this default. */
    fun spanCountForWidth(screenWidthDp: Int): Int = defaultColumnsForWidth(screenWidthDp)

    fun presentation(columnsInput: Int): GridPresentation = when (normalizeColumns(columnsInput)) {
        2 -> GridPresentation(2, 60, 15, 6, 20, 16f, 2, 2)
        3 -> GridPresentation(3, 48, 12, 4, 15, 14f, 2, 1)
        4 -> GridPresentation(4, 40, 10, 3, 12, 12.5f, 2, 0)
        else -> GridPresentation(5, 34, 8, 2, 10, 11f, 2, 0)
    }
}
