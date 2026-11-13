package dev.emyth.app.web.dto

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityDtoTest {
    @Test
    fun hiddenPresentationStateReachesWebClientsWithoutRemovingMessageParts() {
        val message = UIMessage.assistant("[PASS]").copy(
            hideFromChat = true,
            parts = listOf(UIMessagePart.Reasoning("Already returned reasoning"), UIMessagePart.Text("[PASS]")),
        )

        val dto = message.toDto()

        assertTrue(dto.hideFromChat)
        assertEquals(message.id.toString(), dto.id)
        assertSame(message.parts, dto.parts)
    }

    @Test
    fun legacyWebMessagesRemainVisibleWhenTheNewFlagIsAbsent() {
        val legacy = """{"id":"legacy","role":"ASSISTANT","parts":[],"createdAt":"2026-11-12T10:44:00"}"""

        assertFalse(Json.decodeFromString<MessageDto>(legacy).hideFromChat)
    }
}
