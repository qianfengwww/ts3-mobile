package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max

internal class AndroidPlaybackOutput(
    preferredDevice: AudioDeviceInfo?,
    private val onRoutingChanged: (AndroidPlaybackOutput) -> Unit,
    private val routeGuard: BluetoothPlaybackGuard,
    private val onUnsafeRoute: (AndroidPlaybackOutput) -> Unit,
    private val stats: ReceiveStats? = null,
) : PlaybackOutput {
    private val track: AudioTrack
    private val routingListener: AudioRouting.OnRoutingChangedListener
    private val outputLock = Any()
    private val routeRevision = routeGuard.revision()
    private val bluetoothExpected =
        routeGuard.expectsBluetooth() ||
            preferredDevice?.let { AudioRouteDevices.routeKindForDeviceType(it.type) } == AudioRouteKind.BLUETOOTH
    private var silenced = false
    private var closed = false
    private var reportedUnderruns = 0
    private var performanceModeObserved = false
    private var upgradedToBluetooth = false
    private var bluetoothWaitStartedAt: Long? = null

    @Volatile
    private var routedDeviceId: Int? = null

    @Volatile
    override var routedKind: AudioRouteKind? = null
        private set

    init {
        val minimumBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBytes > 0) { "Unable to determine audio buffer size: $minimumBytes" }
        val performanceMode = playbackPerformanceMode(bluetoothExpected)
        val bufferSizeBytes = playbackBufferSizeBytes(minimumBytes, bluetoothExpected)
        track =
            AudioTrack.Builder()
                .setAudioAttributes(voicePlaybackAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(performanceMode)
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Unable to initialize audio output")
        }
        try {
            setPreferredDevice(preferredDevice)
            if (bluetoothExpected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bufferFrames = track.bufferSizeInFrames
                if (bufferFrames > 0) {
                    track.setStartThresholdInFrames(minOf(bufferFrames, BLUETOOTH_START_THRESHOLD_FRAMES).coerceAtLeast(1))
                }
            }
            track.setVolume(0f)
            routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    checkRoute()
                    val deviceId = routing.routedDevice?.id
                    if (deviceId != null) {
                        val previous = routedDeviceId
                        routedDeviceId = deviceId
                        if (previous != null && previous != deviceId) onRoutingChanged(this)
                    }
                }
            track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        } catch (error: RuntimeException) {
            track.release()
            throw error
        }
    }

    override val playbackHead: Long get() = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    override fun sampleClock(): AudioSampleClock? = AndroidAudioClocks.playback(track)

    override fun pollDiagnostics() =
        synchronized(outputLock) {
            if (!closed && stats != null) {
                reportedUnderruns = stats.measureUnderruns(reportedUnderruns) { track.underrunCount }
            }
        }

    override fun start() = track.play()

    override val routeReady: Boolean get() = checkRoute()

    override fun muteImmediately() =
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            track.setVolume(0f)
            Unit
        }

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int {
        if (!checkRoute()) return 0
        return synchronized(outputLock) {
            if (silenced || closed || routeGuard.revision() != routeRevision) {
                0
            } else {
                track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
            }
        }
    }

    private fun checkRoute(): Boolean {
        var upgradeBluetoothTrack = false
        val decision =
            synchronized(outputLock) {
                if (closed || silenced) return false
                val kind = track.routedDevice?.let { AudioRouteDevices.routeKindForDeviceType(it.type) }
                routedKind = kind
                stats?.observeRoute(kind)
                val now = SystemClock.elapsedRealtime()
                val waitStarted = bluetoothWaitStartedAt
                val timedOut = waitStarted != null && now - waitStarted >= BLUETOOTH_ROUTE_TIMEOUT_MS
                routeGuard.evaluate(kind, routeRevision, timedOut).also {
                    bluetoothWaitStartedAt = if (it == PlaybackRouteDecision.WAIT_FOR_BLUETOOTH) waitStarted ?: now else null
                    val needsStandardBluetooth =
                        it == PlaybackRouteDecision.ALLOW && kind == AudioRouteKind.BLUETOOTH && !bluetoothExpected
                    if (needsStandardBluetooth) {
                        track.setVolume(0f)
                        if (!upgradedToBluetooth) {
                            upgradedToBluetooth = true
                            upgradeBluetoothTrack = true
                        }
                    } else {
                        track.setVolume(if (it == PlaybackRouteDecision.ALLOW) 1f else 0f)
                        if (it == PlaybackRouteDecision.ALLOW && kind != null) observePerformanceMode()
                    }
                }
            }
        if (decision == PlaybackRouteDecision.MUTE) onUnsafeRoute(this)
        if (upgradeBluetoothTrack) {
            onRoutingChanged(this)
            return false
        }
        return decision == PlaybackRouteDecision.ALLOW
    }

    private fun observePerformanceMode() {
        if (performanceModeObserved) return
        performanceModeObserved = true
        val diagnostics = stats ?: return
        val mode =
            try {
                track.performanceMode
            } catch (_: IllegalStateException) {
                diagnostics.count(AudioReceiveCounter.DIAGNOSTIC_READ_FAILURES)
                return
            }
        diagnostics.observer?.onEvent(AudioDiagnosticEventKind.OUTPUT_PERFORMANCE_MODE, mode.toLong())
    }

    override fun discard() =
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            track.setVolume(0f)
            track.pause()
            track.flush()
            routedDeviceId = null
        }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (!track.setPreferredDevice(device)) {
            System.err.println("TS3_AUDIO: playback device preference was rejected")
        }
    }

    override fun close() =
        synchronized(outputLock) {
            if (closed) return
            silenced = true
            pollDiagnostics()
            try {
                track.setVolume(0f)
                track.removeOnRoutingChangedListener(routingListener)
            } finally {
                track.release()
                closed = true
                routedKind = null
                stats?.observeRoute(null)
            }
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BLUETOOTH_ROUTE_TIMEOUT_MS = 5_000L
    }
}

private const val BYTES_PER_SAMPLE = 2
internal const val DEFAULT_MIN_BUFFER_BYTES = TalkerJitterPipeline.MIX_TICK_SAMPLES * BYTES_PER_SAMPLE * 4
internal const val BLUETOOTH_BUFFER_MULTIPLIER = 4
internal const val BLUETOOTH_MIN_BUFFER_SAMPLES = 7_680
internal const val BLUETOOTH_MAX_BUFFER_SAMPLES = 11_520
internal const val BLUETOOTH_MIN_BUFFER_BYTES = BLUETOOTH_MIN_BUFFER_SAMPLES * BYTES_PER_SAMPLE
internal const val BLUETOOTH_MAX_BUFFER_BYTES = BLUETOOTH_MAX_BUFFER_SAMPLES * BYTES_PER_SAMPLE
internal const val BLUETOOTH_START_THRESHOLD_FRAMES = 2_400

internal fun voicePlaybackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }
        .build()

internal fun playbackPerformanceMode(bluetoothExpected: Boolean): Int =
    if (bluetoothExpected) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

internal fun playbackBufferSizeBytes(
    minimumBytes: Int,
    bluetoothExpected: Boolean,
): Int {
    require(minimumBytes > 0) { "Invalid minimum audio buffer size: $minimumBytes" }
    val raw =
        if (bluetoothExpected) {
            val scaled =
                (minimumBytes.toLong() * BLUETOOTH_BUFFER_MULTIPLIER)
                    .coerceIn(BLUETOOTH_MIN_BUFFER_BYTES.toLong(), BLUETOOTH_MAX_BUFFER_BYTES.toLong())
                    .toInt()
            max(minimumBytes, scaled)
        } else {
            max(minimumBytes, DEFAULT_MIN_BUFFER_BYTES)
        }
    return if (raw % BYTES_PER_SAMPLE != 0 && raw < Int.MAX_VALUE) raw + 1 else raw
}
