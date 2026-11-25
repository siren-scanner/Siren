package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubUpdatesTest {
    @Test
    fun `versions compare as numbers`() {
        assertEquals(1, GitHubUpdates.compare("1.0.89", "1.0.88"))
        assertEquals(1, GitHubUpdates.compare("1.0.100", "1.0.99"))
        assertEquals(0, GitHubUpdates.compare("1.0.88", "1.0.88"))
        assertEquals(-1, GitHubUpdates.compare("1.0.88", "1.1.0"))
        assertEquals(0, GitHubUpdates.compare("1.0.88-test", "1.0.88"))
    }
}
