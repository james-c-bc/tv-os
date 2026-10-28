package io.github.james_c_bc.home

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class GridTest {
    private fun app(label: String, pkg: String = "com.example.${label.lowercase()}") =
        App(label, pkg, "$pkg.MainActivity")

    private fun labels(rows: List<List<App>>) = rows.map { row -> row.map { it.label } }

    private fun rows(vararg apps: App) = gridRows(apps.toList(), "io.github.james_c_bc.home", Locale.UK)

    @Test
    fun sortsAlphabetically() {
        assertEquals(
            listOf(listOf("Kodi", "Netflix", "YouTube")),
            labels(rows(app("YouTube"), app("Kodi"), app("Netflix"))),
        )
    }

    @Test
    fun ignoresCaseWhenSorting() {
        assertEquals(
            listOf(listOf("apple", "Banana", "cherry")),
            labels(rows(app("cherry"), app("Banana"), app("apple"))),
        )
    }

    @Test
    fun ignoresAccentsWhenSorting() {
        assertEquals(
            listOf(listOf("Eagle", "Éclair", "Ferry")),
            labels(rows(app("Ferry"), app("Éclair"), app("Eagle"))),
        )
    }

    @Test
    fun putsFivePerRow() {
        val apps = ('A'..'L').map { app(it.toString()) }.toTypedArray()
        assertEquals(
            listOf(
                listOf("A", "B", "C", "D", "E"),
                listOf("F", "G", "H", "I", "J"),
                listOf("K", "L"),
            ),
            labels(rows(*apps)),
        )
    }

    @Test
    fun leavesHomeItselfOut() {
        assertEquals(
            listOf(listOf("Kodi")),
            labels(rows(app("Home", "io.github.james_c_bc.home"), app("Kodi"))),
        )
    }

    @Test
    fun keepsAppsWithTheSameLabelInAStableOrder() {
        val b = app("TV", "com.b")
        val a = app("TV", "com.a")
        assertEquals(listOf(listOf(a, b)), rows(b, a))
    }

    @Test
    fun noAppsMeansNoRows() {
        assertEquals(emptyList<List<App>>(), rows())
    }
}
