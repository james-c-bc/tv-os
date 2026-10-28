package io.github.james_c_bc.home

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class GridTest {
    private fun app(label: String, pkg: String = "com.example.${label.lowercase()}") =
        App(label, pkg, "$pkg.MainActivity")

    private fun labels(rows: List<List<App>>) = rows.map { row -> row.map { it.label } }

    private fun rows(vararg apps: App) = gridRows(apps.toList(), "io.github.james_c_bc.home", locale = Locale.UK)

    private fun rows(order: String, vararg apps: App) =
        gridRows(apps.toList(), "io.github.james_c_bc.home", AppOrder.parse(order), Locale.UK)

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

    @Test
    fun putsPinnedAppsFirstInFileOrderThenTheRestAlphabetically() {
        val order = """
            com.example.youtube
            com.example.netflix
        """.trimIndent()
        assertEquals(
            listOf(listOf("YouTube", "Netflix", "Apple", "Kodi", "Zoo")),
            labels(rows(order, app("Kodi"), app("Netflix"), app("Zoo"), app("YouTube"), app("Apple"))),
        )
    }

    @Test
    fun hidesDashedPackages() {
        val order = """
            com.example.netflix
            -com.example.guard
            - com.example.manual
        """.trimIndent()
        assertEquals(
            listOf(listOf("Netflix", "Kodi")),
            labels(rows(order, app("Guard"), app("Kodi"), app("Manual"), app("Netflix"))),
        )
    }

    @Test
    fun hidingWinsOverPinning() {
        val order = """
            com.example.guard
            -com.example.guard
        """.trimIndent()
        assertEquals(listOf(listOf("Kodi")), labels(rows(order, app("Guard"), app("Kodi"))))
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val order = """
            # Streaming first

            com.example.netflix   # Netflix
              com.example.kodi
            -com.example.guard # TCL Guard
            #com.example.apple
        """.trimIndent()
        assertEquals(
            listOf(listOf("Netflix", "Kodi", "Apple")),
            labels(rows(order, app("Apple"), app("Guard"), app("Kodi"), app("Netflix"))),
        )
    }

    @Test
    fun skipsListedPackagesThatArentInstalled() {
        val order = """
            com.example.missing
            com.example.netflix
            -com.example.gone
        """.trimIndent()
        assertEquals(
            listOf(listOf("Netflix", "Kodi")),
            labels(rows(order, app("Kodi"), app("Netflix"))),
        )
    }

    @Test
    fun keepsAPackageListedTwiceAtItsFirstPlace() {
        val order = """
            com.example.netflix
            com.example.kodi
            com.example.netflix
        """.trimIndent()
        assertEquals(
            listOf(listOf("Netflix", "Kodi")),
            labels(rows(order, app("Kodi"), app("Netflix"))),
        )
    }

    @Test
    fun keepsAPinnedPackagesActivitiesTogetherAlphabetically() {
        val order = "com.example.tv"
        val live = App("Live", "com.example.tv", "Live")
        val guide = App("Guide", "com.example.tv", "Guide")
        assertEquals(
            listOf(listOf("Guide", "Live", "Apple")),
            labels(rows(order, app("Apple"), live, guide)),
        )
    }

    @Test
    fun pinnedAppsWrapOntoTheNextRow() {
        val order = ('A'..'F').reversed().joinToString("\n") { "com.example.${it.lowercaseChar()}" }
        val apps = ('A'..'G').map { app(it.toString()) }.toTypedArray()
        assertEquals(
            listOf(listOf("F", "E", "D", "C", "B"), listOf("A", "G")),
            labels(rows(order, *apps)),
        )
    }

    @Test
    fun emptyFileMeansAlphabetical() {
        assertEquals(
            listOf(listOf("Kodi", "Netflix")),
            labels(rows("", app("Netflix"), app("Kodi"))),
        )
    }

    @Test
    fun staysAtTheTopForTheFirstTwoRows() {
        assertEquals(null, topRow(0))
        assertEquals(null, topRow(1))
    }

    @Test
    fun keepsTheRowAboveInViewFurtherDown() {
        assertEquals(1, topRow(2))
        assertEquals(3, topRow(4))
    }
}
