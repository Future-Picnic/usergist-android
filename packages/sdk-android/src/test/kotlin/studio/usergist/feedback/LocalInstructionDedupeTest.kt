package studio.usergist.feedback

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import studio.usergist.feedback.internal.LocalInstructionDedupe
import studio.usergist.feedback.internal.Storage

class LocalInstructionDedupeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `matching instruction is consumed once after restart`() {
        val storage = Storage.forTest(temporaryFolder.newFolder("dedupe"))
        val key = "inapp.show:message-1:event:event-1"

        LocalInstructionDedupe(storage, Json).remember(key)
        val restored = LocalInstructionDedupe(storage, Json)

        assertTrue(restored.consume(key))
        assertFalse(restored.consume(key))
        assertFalse(LocalInstructionDedupe(storage, Json).contains(key))
    }

    @Test
    fun `only latest two hundred instructions are retained`() {
        val storage = Storage.forTest(temporaryFolder.newFolder("bounded"))
        val dedupe = LocalInstructionDedupe(storage, Json)

        repeat(205) { index -> dedupe.remember("inapp.show:message:event:$index") }

        assertEquals(200, dedupe.size())
        assertFalse(dedupe.contains("inapp.show:message:event:0"))
        assertTrue(dedupe.contains("inapp.show:message:event:204"))
    }
}
