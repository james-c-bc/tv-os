package io.github.james_c_bc.home

import java.text.Collator
import java.util.Locale

/** A TV app on the grid: one of its LEANBACK_LAUNCHER activities. */
data class App(val label: String, val packageName: String, val activityName: String)

/** Tiles per row. */
const val COLUMNS = 5

/**
 * The owner's app order, from apps.txt: [pinned] packages go first, in this
 * order, and [hidden] packages are left off the grid.
 */
data class AppOrder(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
    companion object {
        /**
         * Reads an app order file: one package per line, with an optional
         * `# comment`. A line starting with `-` hides that package. Blank
         * lines are ignored, and a package listed twice keeps its first place.
         */
        fun parse(text: String): AppOrder {
            val pinned = LinkedHashSet<String>()
            val hidden = mutableSetOf<String>()
            for (line in text.lines()) {
                val entry = line.substringBefore('#').trim()
                when {
                    entry.startsWith("-") -> entry.removePrefix("-").trim().takeIf { it.isNotEmpty() }?.let { hidden += it }
                    entry.isNotEmpty() -> pinned += entry
                }
            }
            return AppOrder(pinned.toList(), hidden)
        }
    }
}

/**
 * Lays [apps] out as rows of [columns]. Packages pinned in [order] come
 * first, in its order, then the rest alphabetical by label. Case and accents
 * don't affect the order. Hidden packages, and Home's own entry, [self], are
 * left out.
 */
fun gridRows(
    apps: List<App>,
    self: String,
    order: AppOrder = AppOrder(),
    locale: Locale = Locale.getDefault(),
    columns: Int = COLUMNS,
): List<List<App>> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    // Unpinned apps sort after every pinned one.
    val place = order.pinned.withIndex().associate { (index, pkg) -> pkg to index }
    return apps
        .filter { it.packageName != self && it.packageName !in order.hidden }
        .sortedWith(
            compareBy<App> { place[it.packageName] ?: Int.MAX_VALUE }
                .thenBy(collator) { it.label }
                .thenBy { it.packageName }
                .thenBy { it.activityName },
        )
        .chunked(columns)
}
