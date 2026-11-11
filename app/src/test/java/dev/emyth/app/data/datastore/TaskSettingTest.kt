package dev.emyth.app.data.datastore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskSettingTest {
    @Test
    fun publishingANewTaskReplacesItsDefinitionAndClearsThePreviousSubmission() {
        val previous = TaskSetting(
            content = "旧任务",
            type = "photo",
            deadlineMillis = 1_000L,
            punishment = "旧惩罚",
            submission = "file:///old-task.jpg",
            submittedAt = 900L,
            status = "submitted",
        )

        val published = previous.withNewTask("现在去喝水", "text", 60_000L, "提醒一次")

        assertEquals("现在去喝水", published.content)
        assertEquals("text", published.type)
        assertEquals(60_000L, published.deadlineMillis)
        assertEquals("提醒一次", published.punishment)
        assertEquals("", published.submission)
        assertEquals(0L, published.submittedAt)
        assertEquals("pending", published.status)
        assertEquals("file:///old-task.jpg", previous.submission)
    }

    @Test
    fun publishingANewTaskPreservesAllThreeCustomColors() {
        val previous = TaskSetting(
            backgroundColor = 0xFF123456L,
            buttonColor = 0xFF789ABCL,
            countdownColor = 0xFFDEF012L,
        )

        val published = previous.withNewTask("拍一张作业照片", "photo", 120_000L, "")

        assertEquals(previous.backgroundColor, published.backgroundColor)
        assertEquals(previous.buttonColor, published.buttonColor)
        assertEquals(previous.countdownColor, published.countdownColor)
        assertEquals("photo", published.type)
    }

    @Test
    fun everyPreviousTaskStateStartsFreshWhenANewTaskIsPublished() {
        listOf("none", "pending", "submitted", "approved", "rejected").forEach { status ->
            val previous = TaskSetting(
                status = status,
                submission = "之前的文字提交",
                submittedAt = 123L,
            )

            val published = previous.withNewTask("新任务", "text", 456L, "")

            assertEquals(status, "pending", published.status)
            assertEquals(status, "", published.submission)
            assertEquals(status, 0L, published.submittedAt)
        }
    }

    @Test
    fun publishingANewTaskPreservesACombinationOfCustomAndAutomaticColors() {
        val previous = TaskSetting(buttonColor = 0xFF123456L)

        val published = previous.withNewTask("新任务", "text", 456L, "")

        assertNull(published.backgroundColor)
        assertEquals(previous.buttonColor, published.buttonColor)
        assertNull(published.countdownColor)
    }

    @Test
    fun legacyTaskSettingsWithoutColorFieldsCanStillBeLoadedAndReplaced() {
        val legacy = Json.decodeFromString<TaskSetting>(
            """{
                "content": "旧任务",
                "type": "photo",
                "deadlineMillis": 1000,
                "punishment": "旧惩罚",
                "submission": "file:///old-task.jpg",
                "submittedAt": 900,
                "status": "submitted"
            }""".trimIndent()
        )

        val published = legacy.withNewTask("新任务", "text", 2000L, "")

        assertEquals("新任务", published.content)
        assertEquals(2000L, published.deadlineMillis)
        assertEquals("", published.punishment)
        assertEquals("pending", published.status)
        assertEquals("", published.submission)
        assertEquals(0L, published.submittedAt)
        assertNull(published.backgroundColor)
        assertNull(published.buttonColor)
        assertNull(published.countdownColor)
    }

    @Test
    fun emptyLegacySettingsUseDefaultsAndPublishedTaskColorsSurviveSerialization() {
        val defaults = Json.decodeFromString<TaskSetting>("{}")
        assertEquals("none", defaults.status)
        assertNull(defaults.backgroundColor)
        assertNull(defaults.buttonColor)
        assertNull(defaults.countdownColor)

        val configured = Json.decodeFromString<TaskSetting>(
            """{"backgroundColor":4279383126,"buttonColor":4286094012,"countdownColor":4292792338}"""
        )
        val published = configured.withNewTask("新任务", "text", 2000L, "")

        assertEquals(published, Json.decodeFromString<TaskSetting>(Json.encodeToString(published)))
    }
}
