package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseNotesTest {
    @Test
    fun `parses releases and bullets`() {
        val r = ReleaseNotes.parse("# Title\n\nIntro\n\n## 1.0.2 · Nov 5\n- One\n- Two\n\n## 1.0.1 · Nov 4\nPlain line\n")
        assertEquals(listOf("1.0.2 · Nov 5", "1.0.1 · Nov 4"), r.map { it.title })
        assertEquals(listOf("- One", "- Two"), r[0].lines)
        assertEquals(listOf("Plain line"), r[1].lines)
    }

    @Test
    fun `Siren leaves out Siren Test versions`() {
        val r = ReleaseNotes.parse("## 1.0.3-test · c\n- x\n## 1.0.3 · b\n- y\n## 1.0.2-test · a\n- z\n")
        assertEquals(listOf("1.0.3 · b"), ReleaseNotes.forChannel(r, test = false).map { it.title })
        assertEquals(3, ReleaseNotes.forChannel(r, test = true).size)
    }

    @Test
    fun `the shipped notes cover the version being built, in its own channel`() {
        // The open-source edition has its own notes; Siren and Siren Test use the maintainer's private ones.
        val file = if (BuildConfig.UPDATE_URL.isEmpty() && !BuildConfig.TEST_BUILD) File("src/foss/assets/RELEASE_NOTES.md")
        else File("../private/assets/RELEASE_NOTES.md")
        if (!file.exists()) return
        val notes = ReleaseNotes.forChannel(ReleaseNotes.parse(file.readText()), BuildConfig.TEST_BUILD)
        // Siren's versions keep counting while features wait in Siren Test; publish-update.sh checks those when they ship.
        if (BuildConfig.TEST_BUILD) {
            assertTrue("no release notes for ${BuildConfig.VERSION_NAME}", notes.any { it.title.startsWith("${BuildConfig.VERSION_NAME} ") })
        }
        assertTrue(notes.all { it.lines.isNotEmpty() })
    }
}
