package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ManualTopicsTest {
    /** The manual's topic buttons jump to sections by title; a renamed section broke "Cameras" once. */
    @Test
    fun `every topic button has a section, and every section a button`() {
        val src = File("src/main/java/app/sirenscout/Manual.kt").readText()
        val sections = Regex("""Section\("([^"]+)"\)""").findAll(src).map { it.groupValues[1] }.toSet()
        val block = src.substring(src.indexOf("private val TOPICS"), src.indexOf("\n)\n", src.indexOf("private val TOPICS")))
        val topics = Regex(""""([^"]+)" to "[^"]+"""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals("topic buttons with no section", emptySet<String>(), topics - sections)
        assertEquals("sections with no topic button", emptySet<String>(), sections - topics)
    }
}
