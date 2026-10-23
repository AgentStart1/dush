package com.storyteller_f.dush.agent.band

/** Connection-owned trailing debounce. Times are monotonic milliseconds supplied by the caller. */
internal class BandReplyStream(private val quietPeriodMs: Long = 600) {
    private var content = ""
    private var complete = false
    private var readyAt = 0L
    private var sent: String? = null

    val finished: Boolean get() = complete && sent == content

    fun offer(text: String, final: Boolean, now: Long) {
        if (complete) return
        val points = text.replace("\u0000", "").codePoints().limit(300).toArray()
        val preview = String(points, 0, points.size)
        if (preview != content || final) readyAt = if (final) now else now + quietPeriodMs
        content = preview
        complete = final
    }

    fun next(now: Long): String? {
        if (content.isBlank() || content == sent || now < readyAt) return null
        sent = content
        return content
    }
}
