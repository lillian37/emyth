package dev.emyth.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeaOrderSettingTest {
    @Test
    fun `missing stored fields require opt in and explicit assistant selection`() {
        val setting = Json.decodeFromString<TeaOrderSetting>("{}")
        assertFalse(setting.enabled)
        assertNull(setting.assistantId)
        assertEquals(DEFAULT_TEA_ORDER_KEYWORDS, setting.keywords)
        assertEquals(DEFAULT_TEA_ORDER_PROMPT, setting.effectivePrompt)
        assertEquals(0L, setting.enabledSinceMillis)
    }

    @Test
    fun `chosen assistant keywords prompt and activation time survive serialization`() {
        val setting = TeaOrderSetting(
            enabled = true,
            assistantId = "explicit-assistant",
            keywords = listOf("奶茶", "自选品牌"),
            prompt = "这是自定义内容\n请主动联系我。",
            enabledSinceMillis = 123_456L,
        )
        assertEquals(setting, Json.decodeFromString<TeaOrderSetting>(Json.encodeToString(setting)))
    }

    @Test
    fun `missing or deleted assistant disables reminders without choosing another assistant`() {
        assertFalse(TeaOrderSetting(enabled = true).normalized(setOf("another-assistant")).enabled)
        val setting = TeaOrderSetting(enabled = true, assistantId = "deleted-assistant")
            .normalized(setOf("another-assistant"))
        assertFalse(setting.enabled)
        assertEquals("deleted-assistant", setting.assistantId)
        assertTrue(setting.copy(assistantId = "another-assistant").copy(enabled = true)
            .normalized(setOf("another-assistant")).enabled)
    }

    @Test
    fun `keyword normalization removes blanks duplicates and control characters and bounds input`() {
        val words = listOf(" ", " CoCo ", "coco", "奶\u0000茶") +
            (0..80).map { "brand$it" + "x".repeat(60) }
        val setting = TeaOrderSetting(keywords = words).normalized()
        assertEquals(MAX_TEA_ORDER_KEYWORDS, setting.keywords.size)
        assertEquals(listOf("CoCo", "奶茶"), setting.keywords.take(2))
        assertTrue(setting.keywords.all { it.length <= MAX_TEA_ORDER_KEYWORD_LENGTH })
    }

    @Test
    fun `empty keyword list cannot enable reminders and blank prompt uses default`() {
        val setting = TeaOrderSetting(enabled = true, assistantId = "chosen", keywords = listOf(" "), prompt = " ")
            .normalized(setOf("chosen"))
        assertFalse(setting.enabled)
        assertEquals(DEFAULT_TEA_ORDER_PROMPT, setting.effectivePrompt)
    }

    @Test
    fun `single character keywords cannot enable reminders and mixed input keeps valid words`() {
        val setting = TeaOrderSetting(enabled = true, assistantId = "chosen", keywords = listOf("茶", "奶"))
        val singleOnly = setting.normalized(setOf("chosen"))
        assertFalse(singleOnly.enabled)
        assertTrue(singleOnly.keywords.isEmpty())
        val mixed = setting.copy(keywords = listOf("茶", "奶茶", " 奶 ", "喜茶"))
            .normalized(setOf("chosen"))
        assertTrue(mixed.enabled)
        assertEquals(listOf("奶茶", "喜茶"), mixed.keywords)
    }

    @Test
    fun `first enable and changing active assistant start a new activation window`() {
        val disabled = TeaOrderSetting(assistantId = "a", enabledSinceMillis = 100L)
        val enabled = disabled.copy(enabled = true).withActivationTime(disabled, 200L)
        assertEquals(200L, enabled.enabledSinceMillis)
        val changed = enabled.copy(assistantId = "b").withActivationTime(enabled, 300L)
        assertEquals(300L, changed.enabledSinceMillis)
    }

    @Test
    fun `editing active config carries activation window but re-enabling resets it`() {
        val enabled = TeaOrderSetting(enabled = true, assistantId = "a", enabledSinceMillis = 100L)
        val edited = enabled.copy(prompt = "custom").withActivationTime(enabled, 200L)
        assertEquals(100L, edited.enabledSinceMillis)
        val disabled = edited.copy(enabled = false).withActivationTime(edited, 300L)
        assertEquals(100L, disabled.enabledSinceMillis)
        val reenabled = disabled.copy(enabled = true).withActivationTime(disabled, 400L)
        assertEquals(400L, reenabled.enabledSinceMillis)
    }

    @Test
    fun `invalid timestamps cannot expand activation window`() {
        val legacy = TeaOrderSetting(enabled = true, assistantId = "a", enabledSinceMillis = -1L).normalized()
        assertEquals(0L, legacy.enabledSinceMillis)
        assertEquals(500L, legacy.withActivationTime(legacy, 500L).enabledSinceMillis)
    }
}
