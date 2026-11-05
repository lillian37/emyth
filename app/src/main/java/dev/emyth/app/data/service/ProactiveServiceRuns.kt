package dev.emyth.app.data.service

/** Tracks starts independently: a rejected duplicate must not stop another start's foreground work. */
internal class ProactiveServiceRuns {
    private val activeStarts = mutableSetOf<Int>()
    private var latestStartId: Int? = null

    @Synchronized
    fun start(startId: Int) {
        latestStartId = startId
        activeStarts.add(startId)
    }

    @Synchronized
    fun finish(startId: Int): Int? {
        if (!activeStarts.remove(startId)) return null
        return latestStartId.takeIf { activeStarts.isEmpty() }
    }

    @Synchronized
    fun canStop(startId: Int): Boolean = activeStarts.isEmpty() && latestStartId == startId
}
