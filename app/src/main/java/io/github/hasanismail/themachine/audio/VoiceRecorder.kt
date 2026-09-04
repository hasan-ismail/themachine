/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.hasanismail.themachine.stt.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlin.math.sqrt

/** What the recorder is doing, for the overlay to draw. */
sealed interface CaptureEvent {
    /** Live level in [0, 1], for the meter. Emitted per frame. */
    data class Level(val amplitude: Float) : CaptureEvent

    /** Speech detected; the trailing-silence timer is now meaningful. */
    data object SpeechStarted : CaptureEvent

    /** Capture finished with audio to transcribe. */
    data class Finished(val samples: FloatArray, val reason: StopReason) : CaptureEvent {
        // Arrays compare by identity, which would make the generated equals misleading.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Finished && reason == other.reason && samples.contentEquals(other.samples))

        override fun hashCode(): Int = HASH_SEED * samples.contentHashCode() + reason.hashCode()

        private companion object {
            const val HASH_SEED = 31
        }
    }

    /** Capture could not start. */
    data class Failed(val reason: String) : CaptureEvent

    /**
     * Everything heard so far, emitted while the user is still speaking.
     *
     * Not a data class: an array in one gives value semantics that copy badly and lint
     * rightly objects. Nothing compares these anyway — each is transcribed and dropped.
     */
    class Snapshot(val samples: FloatArray) : CaptureEvent
}

enum class StopReason {
    /** Trailing silence after speech — the normal ending. */
    ENDPOINT,

    /** Hit the hard cap without ever detecting an end. */
    MAX_DURATION,

    /** The caller stopped it. */
    CANCELLED,

    /** Nothing above the noise floor for long enough that there is nothing to send. */
    NO_SPEECH,
}

/**
 * Opens the microphone, streams level events, and stops when [Endpointer] says the
 * speaker has finished.
 *
 * Cold flow: capture starts on collection and the AudioRecord is released when
 * collection ends, so cancelling a session cannot leave the microphone open.
 */
class VoiceRecorder {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun capture(endpointer: Endpointer = Endpointer()): Flow<CaptureEvent> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            WhisperEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            trySend(CaptureEvent.Failed("This device cannot record at 16 kHz mono."))
            close()
            return@callbackFlow
        }

        // Try each source in turn. VOICE_RECOGNITION gives the speech-tuned chain when it
        // works, but after an audio-route change it can open and then feed pure silence —
        // a state the upstream project hits too. MIC is the fallback for both a failed init
        // and a silent source: capture() owns the source loop so one that opens but hears
        // nothing is abandoned the same way as one that never opened.
        var everOpened = false
        var keepTrying = true
        var index = 0
        while (keepTrying && index < SOURCES.size) {
            val source = SOURCES[index]
            val record = buildRecordOn(source, minBuffer)
            if (record != null) {
                everOpened = true
                val silent = runAttempt(record, source, endpointer)
                keepTrying = silent && index < SOURCES.lastIndex
                if (keepTrying) {
                    Log.w(TAG, "source $source opened but was silent; reopening on the next source")
                }
            }
            index++
        }
        if (!everOpened) {
            trySend(CaptureEvent.Failed("Microphone unavailable — is another app using it?"))
        }
        close()

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * One capture on one source. Returns true only if the source proved silent — it opened
     * but fed no audio — so the caller can reopen on the next source. A normal end (an
     * endpoint, or a dismissal) returns false: there is nothing to retry.
     */
    private suspend fun ProducerScope<CaptureEvent>.runAttempt(
        record: AudioRecord,
        source: Int,
        endpointer: Endpointer,
    ): Boolean {
        val stats = CaptureStats()
        // AudioRecord.read is an uninterruptible native call, so a cancelled collector does
        // not end the pump — it waits for the current read. stop() unblocks it; without this
        // a dismissal left the microphone open behind whatever the user went back to.
        val stopOnCancel = currentCoroutineContext().job.invokeOnCompletion { runCatching { record.stop() } }
        return try {
            record.startRecording()
            // The one moment that decides whether the user is heard. Everything before it
            // is a window in which their words do not exist.
            Log.i(TAG, "microphone open (source=$source)")
            pump(record, endpointer, stats) == PumpResult.SILENT
        } catch (e: IllegalStateException) {
            Log.w(TAG, "capture failed", e)
            trySend(CaptureEvent.Failed(e.message ?: "Recording failed"))
            false
        } finally {
            // In the finally, not after the loop: a dismissal cancels the coroutine while
            // the loop is blocked in read(), and the tail never runs. The finally always
            // does, so the outcome of a summon the user gave up on is still recorded.
            Log.i(
                TAG,
                "capture done: source=$source reason=${stats.reason} frames=${stats.frames} " +
                    "peak=${"%.4f".format(stats.peak)} speechDetected=${stats.speaking}",
            )
            stopOnCancel.dispose()
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * What a capture actually did, so the finally can report it. A peak of ~0 across many
     * frames means the source opened but fed silence (a known VOICE_RECOGNITION state after
     * an audio-route change); few frames with no speech means it was dismissed early.
     */
    private class CaptureStats {
        var frames = 0
        var peak = 0f
        var speaking = false
        var reason = StopReason.CANCELLED
    }

    /** Reads frames until the endpointer stops it, the source proves silent, or it is cancelled. */
    private suspend fun ProducerScope<CaptureEvent>.pump(
        record: AudioRecord,
        endpointer: Endpointer,
        stats: CaptureStats,
    ): PumpResult {
        val collected = ArrayList<Float>(WhisperEngine.SAMPLE_RATE * INITIAL_SECONDS)
        val buffer = ShortArray(FRAME_SAMPLES)
        var framesSinceSnapshot = 0

        var barrenReads = 0
        while (currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) {
                // Spinning on a stream that has stopped producing burns a core and never
                // ends. A handful in a row means the microphone is gone, not slow.
                if (++barrenReads >= MAX_BARREN_READS) {
                    Log.w(TAG, "capture: $barrenReads reads returned $read; giving up")
                    stats.reason = StopReason.NO_SPEECH
                    trySend(CaptureEvent.Failed("The microphone stopped responding."))
                    return PumpResult.NORMAL
                }
                continue
            }
            barrenReads = 0

            val rms = appendAndMeasure(buffer, read, collected)
            stats.frames++
            if (rms > stats.peak) stats.peak = rms
            trySend(CaptureEvent.Level(displayLevel(rms)))

            // A source can open and then feed pure silence. Once the probe window has
            // passed with no speech and a peak far below any real room's floor (~0.01
            // measured), the source is dead: bail so capture() reopens on the next one,
            // and emit no Finished, since there is nothing worth transcribing.
            if (!stats.speaking && stats.frames >= PROBE_FRAMES && stats.peak < DEAD_SOURCE_PEAK) {
                Log.w(TAG, "capture: source silent, peak=${"%.4f".format(stats.peak)} over ${stats.frames} frames")
                stats.reason = StopReason.NO_SPEECH
                return PumpResult.SILENT
            }

            // Offer the audio so far often enough to feel live, rarely enough that the
            // transcriber is not the reason the microphone stalls. The consumer is free
            // to ignore one it has no time for.
            if (stats.speaking && ++framesSinceSnapshot >= FRAMES_PER_SNAPSHOT) {
                framesSinceSnapshot = 0
                trySend(CaptureEvent.Snapshot(collected.toFloatArray()))
            }

            when (val verdict = endpointer.accept(rms)) {
                FrameVerdict.Continue -> Unit

                FrameVerdict.SpeechBegan -> {
                    stats.speaking = true
                    trySend(CaptureEvent.SpeechStarted)
                }

                is FrameVerdict.Stop -> {
                    // The recording is handed over whatever the reason, NO_SPEECH
                    // included. Discarding it threw away real speech every time the
                    // energy gate misjudged the room -- the user talked, was not
                    // detected, and was told nothing had been heard while a perfectly
                    // good recording of them was dropped. Transcribing costs about
                    // 150 ms and settles the question properly.
                    stats.reason = verdict.reason
                    trySend(CaptureEvent.Finished(collected.toFloatArray(), verdict.reason))
                    return PumpResult.NORMAL
                }
            }
        }
        trySend(CaptureEvent.Finished(collected.toFloatArray(), StopReason.CANCELLED))
        return PumpResult.NORMAL
    }

    /** How a [pump] ended: normally (an endpoint or a cancel), or with a silent source. */
    private enum class PumpResult { NORMAL, SILENT }

    /** Converts one frame to float, accumulates it, and returns its RMS. */
    private fun appendAndMeasure(buffer: ShortArray, read: Int, into: MutableList<Float>): Float {
        var sumSquares = 0.0
        for (i in 0 until read) {
            val sample = buffer[i] / Short.MAX_VALUE.toFloat()
            into.add(sample)
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / read).toFloat()
    }

    /**
     * An initialised recorder on one [source], or null after the retries are spent.
     *
     * AudioRecord construction fails transiently whenever the audio HAL is mid-handoff —
     * coming out of a call, a spoken reply switching the output route, the previous capture
     * still releasing — and returns an object stuck in STATE_UNINITIALIZED. A short retry
     * rides those out. The choice of source, and the fallback between them, is capture()'s.
     */
    @SuppressLint("MissingPermission")
    private suspend fun buildRecordOn(source: Int, minBuffer: Int): AudioRecord? {
        val bufferBytes = maxOf(minBuffer, FRAME_SAMPLES * Short.SIZE_BYTES) * BUFFER_MULTIPLIER
        repeat(INIT_ATTEMPTS) { attempt ->
            val record = runCatching {
                AudioRecord(
                    source,
                    WhisperEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrNull()
            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                if (attempt > 0) Log.i(TAG, "microphone init recovered: source=$source attempt=${attempt + 1}")
                return record
            }
            // state 0 = uninitialised, null = the constructor threw.
            Log.w(
                TAG,
                "microphone init failed: source=$source attempt=${attempt + 1}/$INIT_ATTEMPTS " +
                    "state=${record?.state ?: "null"}",
            )
            record?.release()
            delay(INIT_RETRY_MILLIS)
        }
        return null
    }

    /** RMS is tiny for speech; a square root spreads the useful range across the meter. */
    private fun displayLevel(rms: Float): Float = sqrt(rms.coerceIn(0f, 1f))

    private companion object {
        const val TAG = "TheMachine"

        /**
         * Capture sources in preference order: the speech-tuned recognition chain first,
         * a flat microphone as the fallback when something else holds the recognition input.
         */
        val SOURCES = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        /** Tries per source before moving on — enough to ride out a HAL handoff. */
        const val INIT_ATTEMPTS = 3
        const val INIT_RETRY_MILLIS = 80L

        /** Consecutive failed reads before the microphone is declared gone. */
        const val MAX_BARREN_READS = 10

        /**
         * How long to sample a source before judging it silent, and the peak below which it
         * is. ~0.8 s is well past the mic settling; the threshold sits far under a real room's
         * ~0.01 floor, so only a truly dead source (≈0) trips it — never a quiet one.
         */
        const val PROBE_FRAMES = 40
        const val DEAD_SOURCE_PEAK = 0.0005f

        /** 20 ms per frame — fine enough to endpoint quickly, coarse enough to be cheap. */
        val FRAME_SAMPLES = (WhisperEngine.SAMPLE_RATE * Endpointer.DEFAULT_FRAME_MILLIS / 1000).toInt()

        const val BUFFER_MULTIPLIER = 4
        const val INITIAL_SECONDS = 4

        /** Roughly half a second between partial transcriptions. */
        val FRAMES_PER_SNAPSHOT = (SNAPSHOT_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt()
        const val SNAPSHOT_MILLIS = 500L
    }
}
