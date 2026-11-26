package io.github.ts3mobile.audio.opus

internal class PlaybackTimeline(
    private val output: PlaybackOutput,
    private val reference: PlaybackReferenceSink? = null,
    private val receiveStats: ReceiveStats? = null,
    private val canWrite: () -> Boolean = { true },
) {
    enum class State { IDLE, PLAYING, RECOVERING, DRAINING }

    private class SubmittedSegment(
        val speech: Boolean,
        var remaining: Long,
    )

    var state = State.IDLE
        private set
    var submittedFrames = 0L
        private set
    var playedFrames = 0L
        private set
    var drainTimeoutCount = 0L
        private set
    var talkspurtIdle = false
        private set

    val acceptsPcm: Boolean get() = pending == null

    private val unplayedSegments = ArrayDeque<SubmittedSegment>()
    private var totalSubmittedFrames = 0L
    private var totalPlayedFrames = 0L
    private var previousHead = 0L
    private var recoveryStarted = 0L
    private var drainStarted = 0L
    private var drainBudgetNanos = 0L
    private var pending: ShortArray? = null
    private var pendingOffset = 0
    private var writeStarted = 0L
    private var headOrigin = 0L
    private var startedAt = 0L
    private var pendingArrival: Long? = null
    private var extendedStartupWrite = false

    fun start(now: Long = System.nanoTime()) {
        check(state == State.IDLE)
        submittedFrames = 0L
        playedFrames = 0L
        totalSubmittedFrames = 0L
        totalPlayedFrames = 0L
        unplayedSegments.clear()
        talkspurtIdle = false
        previousHead = output.playbackHead
        headOrigin = previousHead
        startedAt = now
        extendedStartupWrite = false
        reference?.start()
        state = State.PLAYING
    }

    fun tick(
        samples: ShortArray?,
        now: Long,
        originalArrivalNanos: Long? = null,
    ) {
        if (state == State.IDLE) return
        updatePlayedFrames()
        if (extendedStartupWrite && totalPlayedFrames > 0L) {
            extendedStartupWrite = false
            writeStarted = now
        }
        if (samples != null && samples.isNotEmpty()) {
            check(pending == null) { "Playback still has an incomplete write" }
            pending = samples
            pendingOffset = 0
            writeStarted = now
            pendingArrival = originalArrivalNanos
            talkspurtIdle = false
            state = State.PLAYING
        }
        if (pending != null) {
            writePending(now)
            return
        }
        when (state) {
            State.PLAYING -> {
                recoveryStarted = now
                talkspurtIdle = false
                state = State.RECOVERING
            }
            State.RECOVERING -> {
                val elapsed = now - recoveryStarted
                if (elapsed >= RECOVERY_NANOS) talkspurtIdle = true
                val recoveryNanos = if (output.routedKind == AudioRouteKind.BLUETOOTH) BLUETOOTH_RECOVERY_NANOS else RECOVERY_NANOS
                if (elapsed < recoveryNanos || awaitingBluetoothStart(now)) {
                    if (elapsed >= RECOVERY_NANOS && !feedBluetoothKeepAlive()) {
                        discard()
                    }
                    return
                }
                beginDrain(now)
            }
            State.DRAINING -> finishDrain(now)
            State.IDLE -> Unit
        }
    }

    fun discard() {
        state = State.IDLE
        talkspurtIdle = true
        pending = null
        unplayedSegments.clear()
        reference?.discard()
        output.discard()
    }

    private fun beginDrain(now: Long) {
        drainStarted = now
        drainBudgetNanos =
            ((totalSubmittedFrames - totalPlayedFrames) * NANOS_PER_SECOND / SAMPLE_RATE + DRAIN_MARGIN_NANOS)
                .coerceAtMost(MAX_DRAIN_NANOS)
        state = State.DRAINING
        finishDrain(now)
    }

    private fun updatePlayedFrames() {
        val currentHead = output.playbackHead
        val advanced = (currentHead - previousHead) and HEAD_MASK
        if (advanced > HEAD_MASK / 2) return
        previousHead = currentHead
        val newlyPlayed = minOf(advanced, totalSubmittedFrames - totalPlayedFrames)
        if (newlyPlayed <= 0L) return
        totalPlayedFrames += newlyPlayed
        var remaining = newlyPlayed
        while (remaining > 0L && unplayedSegments.isNotEmpty()) {
            val head = unplayedSegments.first()
            val consumed = minOf(remaining, head.remaining)
            if (head.speech) playedFrames = (playedFrames + consumed).coerceAtMost(submittedFrames)
            head.remaining -= consumed
            remaining -= consumed
            if (head.remaining == 0L) unplayedSegments.removeFirst()
        }
    }

    private fun recordSubmitted(
        written: Int,
        speech: Boolean,
    ) {
        val count = written.toLong()
        totalSubmittedFrames += count
        if (speech) submittedFrames += count
        val tail = unplayedSegments.lastOrNull()
        if (tail != null && tail.speech == speech) {
            tail.remaining += count
        } else {
            unplayedSegments.addLast(SubmittedSegment(speech, count))
        }
    }

    private fun feedBluetoothKeepAlive(): Boolean {
        if (output.routedKind != AudioRouteKind.BLUETOOTH || totalPlayedFrames == 0L) return true
        val backlog = totalSubmittedFrames - totalPlayedFrames
        if (backlog >= BLUETOOTH_KEEPALIVE_CUSHION_SAMPLES) return true
        if (!canWrite()) return true
        val needed = minOf((BLUETOOTH_KEEPALIVE_CUSHION_SAMPLES - backlog).toInt(), KEEPALIVE_CHUNK_SAMPLES)
        val written = output.write(SILENCE_SAMPLES, 0, needed)
        if (written < 0) return false
        if (written == 0) return backlog > 0L
        check(written <= needed) { "Audio output write failed: $written" }
        recordSubmitted(written, speech = false)
        receiveStats?.peak(AudioReceivePeak.OUTPUT_BACKLOG_SAMPLES, totalSubmittedFrames - totalPlayedFrames)
        return true
    }

    private fun writePending(now: Long) {
        val samples = pending ?: return
        if (now - writeStarted >= WRITE_TIMEOUT_NANOS) {
            check(awaitingBluetoothStart(now)) { "Audio output write timed out" }
            extendedStartupWrite = true
        }
        repeat(MAX_WRITES_PER_TICK) {
            if (!canWrite()) return
            val remaining = samples.size - pendingOffset
            val writeStart = System.nanoTime()
            val written = output.write(samples, pendingOffset, remaining)
            receiveStats?.peak(AudioReceivePeak.OUTPUT_WRITE_NS, System.nanoTime() - writeStart)
            check(written in 0..remaining) { "Audio output write failed: $written" }
            if (written == 0) {
                receiveStats?.count(AudioReceiveCounter.ZERO_WRITES)
                return
            }
            receiveStats?.count(AudioReceiveCounter.OUTPUT_SAMPLES, written.toLong())
            pendingArrival?.let { arrival -> receiveStats?.let { it.maximum(it.maxCombinedWaitNanos, now - arrival) } }
            if (reference != null) {
                val timestamp = output.sampleClock()
                val position = timestamp?.let { (it.framePosition - headOrigin) and HEAD_MASK }
                val measured =
                    if (timestamp != null && position != null && position <= totalSubmittedFrames + written &&
                        timestamp.nanoTime >= startedAt && now - timestamp.nanoTime in -10_000_000L..500_000_000L
                    ) {
                        AudioSampleClock(position, timestamp.nanoTime).timeAt(totalSubmittedFrames)
                    } else {
                        null
                    }
                val timing =
                    if (measured != null) {
                        RenderTiming(measured, AudioTimingQuality.MEASURED)
                    } else {
                        RenderTiming(
                            now + (totalSubmittedFrames - totalPlayedFrames) * NANOS_PER_SECOND / SAMPLE_RATE,
                            AudioTimingQuality.ESTIMATED,
                        )
                    }
                reference.accepted(samples, pendingOffset, written, totalSubmittedFrames, now, timing)
            }
            pendingOffset += written
            recordSubmitted(written, speech = true)
            receiveStats?.peak(AudioReceivePeak.OUTPUT_BACKLOG_SAMPLES, totalSubmittedFrames - totalPlayedFrames)
            if (pendingOffset == samples.size) {
                pending = null
                return
            }
        }
    }

    private fun awaitingBluetoothStart(now: Long): Boolean =
        output.routedKind == AudioRouteKind.BLUETOOTH && totalSubmittedFrames > 0L && totalPlayedFrames == 0L &&
            now - startedAt < BLUETOOTH_STARTUP_NANOS

    private fun finishDrain(now: Long) {
        if (totalPlayedFrames >= totalSubmittedFrames) {
            discard()
        } else if (now - drainStarted >= drainBudgetNanos) {
            drainTimeoutCount++
            receiveStats?.drainTimeouts?.incrementAndGet()
            discard()
            System.err.println("TS3_AUDIO: playback drain timed out; unplayed tail discarded")
        }
    }

    internal companion object {
        const val RECOVERY_NANOS = 60_000_000L
        const val BLUETOOTH_RECOVERY_NANOS = 35_000_000_000L
        const val BLUETOOTH_KEEPALIVE_CUSHION_SAMPLES = 2_400L
        const val MAX_DRAIN_NANOS = 500_000_000L
        const val WRITE_TIMEOUT_NANOS = 100_000_000L
        const val BLUETOOTH_STARTUP_NANOS = 1_500_000_000L
        private const val KEEPALIVE_CHUNK_SAMPLES = TalkerJitterPipeline.MIX_TICK_SAMPLES
        private const val DRAIN_MARGIN_NANOS = 100_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val SAMPLE_RATE = 48_000
        private const val HEAD_MASK = 0xffff_ffffL
        private const val MAX_WRITES_PER_TICK = 8
        private val SILENCE_SAMPLES = ShortArray(KEEPALIVE_CHUNK_SAMPLES)
    }
}
