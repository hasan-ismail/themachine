/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.hasanismail.themachine.audio.CaptureEvent
import io.github.hasanismail.themachine.audio.Endpointer
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.audio.StopReason
import io.github.hasanismail.themachine.audio.VoiceRecorder
import io.github.hasanismail.themachine.context.MachineFiles
import io.github.hasanismail.themachine.history.QueryLog
import io.github.hasanismail.themachine.history.QueryRecord
import io.github.hasanismail.themachine.history.QuerySource
import io.github.hasanismail.themachine.history.Resolution
import io.github.hasanismail.themachine.llm.LlamaEngine
import io.github.hasanismail.themachine.llm.ModelRouter
import io.github.hasanismail.themachine.llm.PromptDialect
import io.github.hasanismail.themachine.models.ModelArchive
import io.github.hasanismail.themachine.models.ModelAsset
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.settings.MachineSettings
import io.github.hasanismail.themachine.stt.WhisperEngine
import io.github.hasanismail.themachine.tools.ContactLookup
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.ReminderStore
import io.github.hasanismail.themachine.tools.TimeResolver
import io.github.hasanismail.themachine.tools.ToolCall
import io.github.hasanismail.themachine.tools.ToolExecutor
import io.github.hasanismail.themachine.tools.ToolResult
import io.github.hasanismail.themachine.tts.PiperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Where the session is in the listen, understand, act pipeline. */
sealed interface SessionState {
    data object Idle : SessionState
    data object Preparing : SessionState
    data class Listening(
        val level: Float,
        val heardSpeech: Boolean,
        /** What has been made out so far; grows while the user is still talking. */
        val partial: String = "",
    ) : SessionState
    data object Transcribing : SessionState
    data class Thinking(val transcript: String) : SessionState
    data class Done(
        val transcript: String,
        val tool: String,
        val result: ToolResult,
        val timing: Timing,
        /** True if the command ran from the learned-phrase cache, with no model involved. */
        val fromCache: Boolean = false,
    ) : SessionState

    data class Problem(val message: String, val actionable: String? = null) : SessionState
}

/** Where the time went, so a regression against the latency budget is visible. */
data class Timing(val sttMillis: Long, val llmMillis: Long) {
    val totalMillis: Long get() = sttMillis + llmMillis
}

/**
 * Drives one spoken interaction end to end: capture, transcribe, choose a tool, run it.
 *
 * Both engines are loaded once per summon and freed when the overlay closes. Loading is
 * the dominant cost, so paying it per utterance would spend the whole latency budget
 * before any audio arrived.
 */
class VoiceSession(private val context: Context, private val scope: CoroutineScope) {

    private val recorder = VoiceRecorder()
    private val whisper = WhisperEngine(context)
    private val llama = LlamaEngine(context)
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val settings = MachineSettings(context)
    private val files = MachineFiles(context)
    private val executor = ToolExecutor(context, ReminderStore(context), ContactLookup(context))
    private val piper = PiperEngine()
    private val router = ModelRouter(context)
    private val cache = CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
    private val history = QueryLog(context)

    /** Where the current command came from, for the record. */
    private var source = QuerySource.VOICE

    /** The capture coroutine, kept so a typed command can stop the microphone. */
    private var listening: Job? = null

    /** Everything after the microphone closes: transcribe, parse, act, speak. */
    private var processing: Job? = null

    /**
     * Serialises loading the language model. It is started in the background as soon as
     * the session opens and again, if still needed, when a command arrives; without
     * this the two would race and load the model twice.
     */
    private val modelLoading = Mutex()

    /**
     * Outlives the session, for the work that must finish after it closes.
     *
     * The session's own scope belongs to the overlay's composition and is cancelled the
     * moment it goes away, which is exactly when the engines need freeing.
     */
    private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The prompt-cache write, kept so the model is not freed out from under it. */
    private var cacheWrite: Job? = null

    /**
     * Guards the transcriber, which is one engine and cannot run twice at once.
     *
     * Partial passes take the lock only if it is free, so a slow one is skipped rather
     * than queued; the final pass waits, because its result is the one that matters.
     */
    private val transcriber = Mutex()

    /** File name of whatever language model is loaded, for dialect selection. */
    private var loadedModelName: String = ""

    /** Whether this session has already written its prompt cache. */
    private var cacheWritten = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Begins listening. Anything that can go wrong before the microphone opens is
     * reported with something the user can actually do about it, not a silent no-op.
     */
    fun start() {
        source = QuerySource.VOICE
        val previous = listening
        listening = scope.launch {
            // Joined, not merely cancelled: a second summon before the first gave up used
            // to open a second recorder while the first still held the microphone, and
            // the device refuses the second one.
            //
            // Bounded, though. An unbounded join is worse than the contention it avoids:
            // if the previous capture is wedged in a native read that never returns, the
            // wait never ends, and every summon after it opens an overlay that never
            // listens and never says why. A stale recorder is the better failure, so
            // after the grace period this proceeds regardless.
            if (previous != null) {
                Log.i(TAG, "summon: waiting for the previous capture")
                val handedOver = withTimeoutOrNull(HANDOVER_MILLIS) { previous.cancelAndJoin() }
                if (handedOver == null) Log.w(TAG, "summon: previous capture would not stop")
            }
            // A new command replaces the old one, including a reply still being spoken.
            processing?.cancel()
            _state.value = SessionState.Preparing
            if (!hasMicrophonePermission()) {
                fail(
                    "The Machine cannot hear you without microphone access.",
                    "Grant Microphone under System access.",
                )
                return@launch
            }
            val sttModel = installed(ModelRole.STT)
            if (sttModel == null) {
                fail("No speech model is installed yet.", "Download one under Models.")
                return@launch
            }

            if (!whisper.isLoaded && !whisper.load(storage.target(sttModel))) {
                fail("The speech model could not be loaded.")
                return@launch
            }
            // The voice is prepared in the background too. Unpacking it is a one-off
            // that costs a few seconds, and doing it here means the first reply is
            // spoken rather than silently skipped.
            prepareVoice()

            // The language model loads in the background while the user is still
            // speaking, so its load time overlaps the utterance instead of following it.
            scope.launch { ensureLanguageModel() }
            listen()
        }
    }

    /**
     * Stops the microphone without ending the session, for someone who would rather type.
     *
     * The engines stay loaded: a typed command still needs the language model, and the
     * voice still speaks the reply.
     */
    fun stopListening() {
        MachineSounds.capturing = false
        MicForegroundService.stop(context)
        // Cancelled but deliberately still referenced. Nulling it here threw away the only
        // handle on a recorder that had not finished releasing the microphone, so the next
        // summon opened a second one on top of it and the audio system made it queue —
        // two and a half seconds during which the overlay is up, looks ready, and hears
        // nothing at all. start() joins whatever is left here before opening its own.
        listening?.cancel()
        val current = _state.value
        if (current is SessionState.Listening || current is SessionState.Preparing) {
            _state.value = SessionState.Idle
        }
    }

    /**
     * Runs a command the user typed rather than spoke.
     *
     * The microphone is stopped first: someone who has started typing has decided not to
     * talk, and a stray sound should not become a second command. Everything after that
     * is the same path speech takes, minus transcription.
     */
    fun submitText(text: String) {
        val typed = text.trim()
        if (typed.isEmpty()) return
        source = QuerySource.TYPED
        stopListening()
        scope.launch {
            _state.value = SessionState.Thinking(typed)
            act(typed, sttMillis = 0)
        }
    }

    /**
     * Unpacks and loads the voice, if one is installed. Never fails the session: an
     * assistant that cannot speak is diminished, not broken, and its reply is on screen.
     */
    private fun prepareVoice() {
        val asset = installed(ModelRole.TTS) ?: return
        if (piper.isLoaded) return
        scope.launch {
            val unpacked = withContext(Dispatchers.IO) {
                ModelArchive.unpack(storage.target(asset), storage.extractedDir(asset))
            }
            if (unpacked) piper.load(storage.extractedDir(asset))
        }
    }

    /**
     * Lint cannot see that [start] refuses to get here without RECORD_AUDIO; the check
     * it wants has already happened one frame up.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun listen() {
        _state.value = SessionState.Listening(level = 0f, heardSpeech = false)
        MachineSounds.capturing = true
        // RECORD_AUDIO is granted "while in use", and this records from an overlay with no
        // activity in the foreground, so the appop hands the app digital zeros instead of
        // audio. A microphone-typed foreground service puts the process into the state the
        // appop checks for. It runs only while recording, so it costs nothing at rest.
        MicForegroundService.start(context)
        // The endpointer ignores the first few frames, which are the microphone opening
        // rather than anything in the room.
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        var finished: CaptureEvent.Finished? = null
        recorder.capture(endpointer).collect { event ->
            when (event) {
                is CaptureEvent.Level -> updateListening { it.copy(level = event.amplitude) }
                CaptureEvent.SpeechStarted -> updateListening { it.copy(heardSpeech = true) }
                is CaptureEvent.Snapshot -> transcribePartial(event.samples)
                is CaptureEvent.Failed -> fail(event.reason)
                is CaptureEvent.Finished -> finished = event
            }
        }

        // Handled after the flow ends rather than inside it, and launched rather than
        // awaited. Running the pipeline within the collector kept this job alive through
        // transcription, the language model and the spoken reply, so the next summon —
        // which has to join this job before it may open the microphone — waited for all
        // of it. Nine seconds of an overlay that is up, looks ready, and is not
        // listening. This job's one remaining duty is the microphone.
        MicForegroundService.stop(context)
        val done = finished ?: return
        processing?.cancel()
        processing = scope.launch { handle(done) }
    }

    /**
     * Transcribes what has been heard so far, so the words appear as they are spoken.
     *
     * Dropped silently if the transcriber is busy or the state has moved on: a partial
     * is worth showing only while it is still current, and never worth delaying the
     * microphone for.
     */
    private fun transcribePartial(samples: FloatArray) {
        if (!whisper.isLoaded || samples.isEmpty()) return
        if (!transcriber.tryLock()) return
        scope.launch {
            try {
                val heard = whisper.transcribe(samples)
                if (heard.text.isNotBlank()) {
                    updateListening { it.copy(partial = heard.text) }
                }
            } finally {
                transcriber.unlock()
            }
        }
    }

    private inline fun updateListening(block: (SessionState.Listening) -> SessionState) {
        val current = _state.value
        if (current is SessionState.Listening) _state.value = block(current)
    }

    private suspend fun handle(event: CaptureEvent.Finished) {
        MachineSounds.capturing = false
        if (event.samples.isEmpty()) {
            fail("I did not hear anything.")
            return
        }
        // A NO_SPEECH verdict is the energy gate's opinion, not a finding of fact, and it
        // is wrong whenever the room was misjudged. Whisper is asked before the user is
        // told they said nothing; if it also comes back empty, they really did.
        val gaveUp = event.reason == StopReason.NO_SPEECH
        MachineSounds.play(MachineSounds.Cue.DISENGAGE, volume = 0.4f)
        _state.value = SessionState.Transcribing

        val heard = transcriber.withLock { whisper.transcribe(event.samples) }
        if (heard.text.isBlank()) {
            fail(if (gaveUp) "I did not hear anything." else "I heard something, but could not make out any words.")
            return
        }
        if (gaveUp) Log.i(TAG, "endpointer heard nothing, but whisper did")
        Log.i(TAG, "heard [${heard.text}] in ${heard.durationMillis} ms")
        _state.value = SessionState.Thinking(heard.text)
        act(heard.text, heard.durationMillis)
    }

    /**
     * Turns the transcript into a tool call and runs it.
     *
     * A phrase the assistant has resolved before is run at once, from the cache, before
     * the model is even consulted — or loaded. Everything else goes through the model,
     * and if the command it produces is one whose meaning is fixed, it is remembered for
     * next time.
     *
     * The grammar guarantees a valid call, so there is no retry loop: a null parse would
     * mean no grammar was applied, which is a programming error rather than a runtime
     * condition.
     */
    private suspend fun act(transcript: String, sttMillis: Long) {
        if (answeredWithoutTheModel(transcript, sttMillis)) return

        if (!ensureLanguageModel()) {
            failCommand(
                transcript,
                "I heard you, but no language model is installed.",
                "Download one under Models.",
            )
            return
        }

        // The dialect owns the prompt, the grammar and the parser together, because a
        // fine-tuned model only behaves if all three match the form it was trained on.
        val dialect = PromptDialect.forModel(loadedModelName)
        val prompt = dialect.buildPrompt(
            transcript = transcript,
            tools = MachineTools.all,
            adminName = settings.adminNameNow(),
            userContext = files.contextForPrompt(),
        )
        // Stop the moment the tool turns out to be a question: what the small model would
        // write next is exactly the part that gets replaced.
        val completion = llama.generate(prompt, dialect.grammar(MachineTools.all), stopAt = dialect.answerMarker)
        Log.i(TAG, "model returned ${completion.text} in ${completion.millis} ms")

        var call = dialect.parse(completion.text)
        if (call == null) {
            failCommand(transcript, "I could not work out what to do with that.")
            return
        }
        var llmMillis = completion.millis

        if (call.tool == MachineTools.ANSWER) {
            // One model in memory at a time. The small one has already done its work for
            // this command and is cheap to bring back; the large one is not, and the two
            // together do not fit alongside everything else the phone is running.
            llama.unload()
            val answer = router.answer(transcript, settings.adminNameNow(), files.contextForPrompt())
            call = if (answer != null) {
                llmMillis += answer.millis
                ToolCall(MachineTools.ANSWER, mapOf("text" to answer.text))
            } else {
                // Said out loud, so it has to name the actual obstacle: telling someone
                // to download a model they already have is worse than saying nothing.
                ToolCall(
                    MachineTools.ANSWER,
                    mapOf(
                        "text" to when (router.lastRefusal) {
                            ModelRouter.Refusal.NO_MEMORY ->
                                "I need more free memory to answer questions."

                            ModelRouter.Refusal.FAILED ->
                                "The larger model would not load, so I cannot answer that."

                            else ->
                                "I can only do that with the larger model. It is under Models."
                        },
                    ),
                )
            }
        }
        carryOut(transcript, call, sttMillis, llmMillis, Resolution.MODEL)
    }

    /**
     * Corrects an hour the model converted badly, using the words it was given.
     *
     * The only place the transcript and the resolved call are both in hand, which is
     * what this needs: the executor sees a call and never the sentence behind it.
     */
    private fun reconciled(transcript: String, call: ToolCall): ToolCall {
        val timed = call.tool == MachineTools.SET_ALARM || call.tool == MachineTools.CREATE_REMINDER
        val stated = call.arguments["hour"]?.toIntOrNull()
        val corrected = if (timed && stated != null) TimeResolver.reconcileHour(transcript, stated) else null
        if (corrected == null || corrected == stated) return call
        Log.i(TAG, "hour corrected from $stated to $corrected for [$transcript]")
        return call.copy(arguments = call.arguments + ("hour" to corrected.toString()))
    }

    /**
     * The two ways a command is answered with no model at all.
     *
     * The clock first: the phone knows the time exactly, and the small model asked for it
     * answered by reading out the notification shade — which was then learned, so it kept
     * doing it. Then the remembered shapes, which is the whole point of remembering them.
     */
    private suspend fun answeredWithoutTheModel(transcript: String, sttMillis: Long): Boolean {
        val call = settledLocally(transcript)
            ?: withContext(Dispatchers.IO) { cache.lookup(transcript) }
            ?: return false
        Log.i(TAG, "settled without the model [$transcript] -> ${call.tool} ${call.arguments}")
        carryOut(transcript, call, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
        return true
    }

    /**
     * The three things the phone can settle exactly, without loading anything.
     *
     * All three were tried as tools first and all three were worse for it. Every tool
     * declared dilutes a 1B measurably — adding the reminder family took routing from one
     * failure to five, and "timer for ten minutes" started coming back with the wall clock
     * in it. Worse, "mark the bins done" resolved to create_reminder, which files a second
     * copy of a task rather than finishing the first: silent, and actively wrong.
     *
     * None of the three needs a model. The clock, the task list, and which task was named
     * are all things the device knows exactly.
     */
    private fun settledLocally(transcript: String): ToolCall? {
        LocalAnswers.of(transcript)?.let {
            return ToolCall(MachineTools.ANSWER, mapOf("text" to it))
        }
        if (TaskQuestion.isAskingForList(transcript)) {
            return ToolCall(MachineTools.LIST_REMINDERS, emptyMap())
        }
        TaskQuestion.completionTarget(transcript)?.let {
            return ToolCall(MachineTools.COMPLETE_REMINDER, mapOf("task" to it))
        }
        return null
    }

    /** Executes a resolved call, then records, remembers, shows and speaks the outcome. */
    private suspend fun carryOut(
        transcript: String,
        call: ToolCall,
        sttMillis: Long,
        llmMillis: Long,
        resolution: Resolution,
    ) {
        var result = executor.execute(reconciled(transcript, call))

        // A question about the screen is answered, not recited. read_screen hands back the
        // full text as `detail`, and without this step the assistant reads the raw
        // accessibility dump out loud — which is exactly what "read the screen and
        // summarise it" produced: a minute of somebody else's chat log, unsummarised.
        // Done here rather than in act() so it covers the cached path too, and in the
        // session rather than the executor, which runs on the main thread and must never
        // be the thing that loads three gigabytes of weights.
        val question = ScreenQuestion.of(transcript, call.arguments["question"])
        if (call.tool == MachineTools.READ_SCREEN && question != null && result.success) {
            val screen = result.detail.orEmpty().ifBlank { result.spoken }
            llama.unload()
            val answered = router.answerAbout(screen, question, settings.adminNameNow())
            result = if (answered != null) {
                result.copy(spoken = answered.text)
            } else {
                // The un-escalated result is still a real answer here, which is not true
                // of any other escalation in the app, so it degrades rather than refuses.
                result.copy(spoken = "I could not summarise that, so here is what it says. ${result.spoken}")
            }
        }

        // Learning and recording both touch the disk, and neither is worth a frame of
        // the reply. Only a call the model produced and that then succeeded is learned:
        // a failure might be a misread, and a cache hit is already known.
        val record = QueryRecord(
            atEpochMillis = System.currentTimeMillis(),
            transcript = transcript,
            source = source,
            resolution = resolution,
            tool = call.tool,
            arguments = call.arguments,
            spoken = result.spoken,
            success = result.success,
            sttMillis = sttMillis,
            llmMillis = llmMillis,
        )
        teardown.launch {
            if (resolution == Resolution.MODEL && result.success) cache.learn(transcript, call)
            history.append(record)
        }
        Log.i(
            TAG,
            "session done: ${call.tool} -> ${result.spoken} " +
                "(stt $sttMillis ms, llm $llmMillis ms, ${resolution.name.lowercase()})",
        )

        MachineSounds.play(
            if (result.success) MachineSounds.Cue.CONFIRM else MachineSounds.Cue.REJECT,
            volume = 0.45f,
        )
        _state.value = SessionState.Done(
            transcript = transcript,
            tool = call.tool,
            result = result,
            timing = Timing(sttMillis, llmMillis),
            fromCache = resolution == Resolution.CACHE,
        )

        // Started while the user is listening to the answer, and on the scope that
        // outlives the session rather than the overlay's own. The write itself survives
        // either way — it is a native call, and cancelling the coroutine around it does
        // not stop it once it has begun — but on the overlay's scope it is only ever one
        // scheduling delay away from not starting at all.
        // Once per session is enough: the cached prefix is the same every time.
        if (!cacheWritten && llama.isLoaded) {
            cacheWritten = true
            cacheWrite = teardown.launch {
                llama.saveState()
                router.saveState()
            }
        }

        // Said after the state is published, so the reply is on screen while it is being
        // spoken rather than after.
        piper.speak(result.spoken)
    }

    /**
     * Loads the language model if it is not loaded, exactly once however many callers
     * ask at the same time. False only if there is no model to load or it will not load.
     */
    private suspend fun ensureLanguageModel(): Boolean = modelLoading.withLock {
        if (llama.isLoaded) return@withLock true
        val asset = installed(ModelRole.LLM) ?: return@withLock false
        loadedModelName = asset.fileName
        llama.load(storage.target(asset))
    }

    /** A failure that happened to a command, so the transcript is worth keeping. */
    private fun failCommand(transcript: String, message: String, actionable: String? = null) =
        fail(message, actionable, transcript)

    private fun fail(message: String, actionable: String? = null, transcript: String = "") {
        // Recorded, not just spoken. The history held eleven entries and zero failures
        // over a period with more than a hundred failed summons, so the one log the user
        // can actually see was the one place the trouble never showed up.
        history.append(
            QueryRecord(
                atEpochMillis = System.currentTimeMillis(),
                transcript = transcript,
                source = source,
                resolution = Resolution.FAILED,
                spoken = message,
                success = false,
            ),
        )
        // Logged as well as shown. Every user-visible failure used to leave no trace at
        // all, which made a session that ended badly indistinguishable from one that
        // never ended, and both of them invisible to a test running over adb.
        Log.i(TAG, "session problem: $message${actionable?.let { " ($it)" } ?: ""}")
        _state.value = SessionState.Problem(message, actionable)
        MachineSounds.play(MachineSounds.Cue.REJECT)
        // Spoken too: the overlay sits over whatever the user was doing, and a problem
        // they have to read is one they may never notice.
        scope.launch { piper.speak(message) }
    }

    /**
     * The registry's default first, then anything else installed for the role.
     *
     * Order matters: without the preference, downloading an experimental model would
     * silently take over the pipeline just by being listed earlier.
     */
    private fun installed(role: ModelRole): ModelAsset? {
        val ready = registry.byRole(role).filter { storage.quickState(it) == ModelState.Ready }
        return ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Frees both models. Called when the overlay goes away, not between utterances. */
    fun release() {
        MicForegroundService.stop(context)
        // Piper and the router stop themselves promptly; whisper and llama take the
        // monitor an in-flight decode holds, and this is the main thread closing an
        // overlay. Freeing them is handed to a scope that outlives the session.
        piper.release()
        router.release()
        teardown.launch {
            // Waited for, not raced: the save walks the very cells nativeFree releases,
            // and a use-after-free here is the same SIGSEGV that state saving already
            // cost once.
            cacheWrite?.join()
            executor.release()
            whisper.unload()
            llama.unload()
        }
        _state.value = SessionState.Idle
    }

    private companion object {
        const val TAG = "TheMachine"

        /** How long a new summon waits for the previous recorder to let go. */
        const val HANDOVER_MILLIS = 400L
    }
}
