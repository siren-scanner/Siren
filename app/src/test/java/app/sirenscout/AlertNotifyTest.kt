package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertNotifyTest {
    @Test
    fun `every category has its notification setting, surveillance on its own`() {
        assertEquals(AlertNotify.Kind.SURVEILLANCE, AlertNotify.forCategory(Category.SURVEILLANCE))
        assertEquals(AlertNotify.Kind.POLICE, AlertNotify.forCategory(Category.POLICE))
        assertEquals(AlertNotify.Kind.OTHER, AlertNotify.forCategory(Category.FLEET))
        Category.entries.forEach { AlertNotify.forCategory(it) }
        // The settings list shows each label once.
        assertEquals(AlertNotify.Kind.entries.size, AlertNotify.Kind.entries.map { it.label }.toSet().size)
    }
}
