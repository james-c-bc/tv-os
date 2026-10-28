package io.github.james_c_bc.home

import java.text.Collator
import java.util.Locale

/** A TV app on the grid: one of its LEANBACK_LAUNCHER activities. */
data class App(val label: String, val packageName: String, val activityName: String)

/** Tiles per row. */
const val COLUMNS = 5

/**
 * Lays [apps] out as rows of [columns], alphabetical by label. Case and
 * accents don't affect the order. Home's own entry, [self], is left out.
 */
fun gridRows(
    apps: List<App>,
    self: String,
    locale: Locale = Locale.getDefault(),
    columns: Int = COLUMNS,
): List<List<App>> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return apps
        .filter { it.packageName != self }
        .sortedWith(
            compareBy<App, String>(collator) { it.label }
                .thenBy { it.packageName }
                .thenBy { it.activityName },
        )
        .chunked(columns)
}
