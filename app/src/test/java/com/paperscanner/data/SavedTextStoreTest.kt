package com.paperscanner.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Titles are what the saved-text library shows in its list, so they have to be
 * predictable: first real line, trimmed, with a sane fallback.
 */
class SavedTextStoreTest {

    @Test
    fun usesTheFirstNonBlankLine() {
        assertEquals("Hello world", SavedTextStore.defaultTitle("Hello world\nsecond line"))
    }

    @Test
    fun skipsLeadingBlankLines() {
        assertEquals("Real title", SavedTextStore.defaultTitle("\n\n   \nReal title\nbody"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("Padded", SavedTextStore.defaultTitle("   Padded   \nmore"))
    }

    @Test
    fun fallsBackToUntitledForEmptyOrBlankText() {
        assertEquals("Untitled", SavedTextStore.defaultTitle(""))
        assertEquals("Untitled", SavedTextStore.defaultTitle("   \n\t  \n"))
    }

    @Test
    fun truncatesLongLinesWithAnEllipsis() {
        val long = "x".repeat(120)
        val title = SavedTextStore.defaultTitle(long)

        assertEquals(41, title.length)
        assertEquals("…", title.last().toString())
        assertEquals("x".repeat(40), title.dropLast(1))
    }

    @Test
    fun keepsShortLinesExactly() {
        val exactly = "y".repeat(40)
        assertEquals(exactly, SavedTextStore.defaultTitle(exactly))
    }
}
