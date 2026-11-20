package dev.emyth.app.data.datastore

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadedSettingsTest {
    @Test
    fun waitsForSavedEnabledSettingsInsteadOfReturningTheDisabledPlaceholder() = runTest {
        val state = MutableStateFlow(settings(initializing = true))
        val loaded = settings(enabled = true)
        val result = async { state.firstLoadedSettings() }
        launch {
            delay(500)
            state.value = loaded
        }

        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(499)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()

        assertSame(loaded, result.await())
        assertTrue(result.await().proactiveMessageSetting.enabled)
        assertEquals(500L, currentTime)
    }

    @Test
    fun savedDisabledSettingsAreReturnedWithoutWaitingForTheSwitchToBeEnabled() = runTest {
        val loaded = settings(enabled = false)

        assertSame(loaded, MutableStateFlow(loaded).firstLoadedSettings())
        assertEquals(0L, currentTime)
    }

    @Test
    fun cancellationWhileLoadingPreventsLateSettingsFromResumingTheCaller() = runTest {
        val state = MutableStateFlow(settings(initializing = true))
        var returned = false
        val reader = launch {
            state.firstLoadedSettings()
            returned = true
        }

        runCurrent()
        advanceTimeBy(100)
        assertFalse(returned)
        reader.cancelAndJoin()
        state.value = settings(enabled = true)
        runCurrent()

        assertTrue(reader.isCancelled)
        assertFalse(returned)
        assertEquals(100L, currentTime)
    }

    @Test
    fun aLoadingDeadlineRemainsCancellationRatherThanADisabledResult() = runTest {
        val state = MutableStateFlow(settings(initializing = true))
        val failure = runCatching {
            withTimeout(100) { state.firstLoadedSettings() }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
        assertEquals(100L, currentTime)
    }

    private fun settings(initializing: Boolean = false, enabled: Boolean = false) = Settings(
        init = initializing,
        providers = emptyList(),
        assistants = emptyList(),
        proactiveMessageSetting = ProactiveMessageSetting(enabled = enabled),
    )
}
