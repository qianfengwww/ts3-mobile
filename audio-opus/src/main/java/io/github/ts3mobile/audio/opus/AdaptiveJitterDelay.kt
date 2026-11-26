package io.github.ts3mobile.audio.opus

import kotlin.math.abs
import kotlin.math.ceil

internal class AdaptiveJitterDelay(private val policy: ReceivePolicy) {
    var targetMs = policy.effectiveBounds().initialMs
        private set
    private var previousSequence: Long? = null
    private var previousArrival = 0L
    private var previousSamples = 0
    private val deviations = ArrayDeque<Double>()
    private var stable = 0
    private var late = 0

    fun observe(
        sequence: Long,
        arrival: Long,
        info: OpusPacketInfo,
    ) {
        if (info.timingUncertain) {
            discontinuity()
            return
        }
        val previous = previousSequence
        if (previous != null && sequence <= previous) return
        late = 0
        if (previous != null && sequence == previous + 1 && previousSamples == info.samples && arrival >= previousArrival) {
            val deviation = abs((arrival - previousArrival) / 1_000_000.0 - previousSamples / 48.0)
            if (deviations.size == ReceivePolicy.JITTER_WINDOW) deviations.removeFirst()
            deviations.addLast(deviation)
            stable++
            if (policy.adaptive && deviations.size == ReceivePolicy.JITTER_WINDOW) {
                val bounds = policy.effectiveBounds()
                val ordered = deviations.sorted()
                val percentile = ordered[ReceivePolicy.JITTER_PERCENTILE_INDEX]
                val desiredMs = bounds.minMs + ReceivePolicy.JITTER_MULTIPLIER * percentile
                val desired =
                    (ceil(desiredMs / ReceivePolicy.TARGET_STEP_MS) * ReceivePolicy.TARGET_STEP_MS).toInt()
                        .coerceIn(bounds.minMs, bounds.maxMs)
                if (desired > targetMs && stable % ReceivePolicy.INCREASE_EVERY_PACKETS == 0) {
                    targetMs = minOf(bounds.maxMs, targetMs + ReceivePolicy.TARGET_STEP_MS)
                }
                if (desired < targetMs && stable >= ReceivePolicy.STABLE_PACKETS) {
                    targetMs = maxOf(bounds.minMs, targetMs - ReceivePolicy.TARGET_STEP_MS)
                    stable = 0
                }
            }
        } else {
            stable = 0
        }
        previousSequence = sequence
        previousArrival = arrival
        previousSamples = info.samples
    }

    fun noteLate() {
        stable = 0
        if (policy.adaptive && ++late >= ReceivePolicy.LATE_PACKETS_TO_INCREASE) {
            val bounds = policy.effectiveBounds()
            targetMs = minOf(bounds.maxMs, targetMs + ReceivePolicy.TARGET_STEP_MS)
            late = 0
        }
    }

    fun discontinuity() {
        previousSequence = null
        stable = 0
        deviations.clear()
        if (policy.adaptive) {
            val bounds = policy.effectiveBounds()
            targetMs = targetMs.coerceIn(bounds.minMs, bounds.maxMs)
        }
    }
}
