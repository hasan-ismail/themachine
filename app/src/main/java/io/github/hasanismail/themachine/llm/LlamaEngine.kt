/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.hasanismail.themachine.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A completion and how long it took. */
data class Completion(val text: String, val millis: Long)

/**
 * Runs the language model.
 *
 * Loaded once per session and freed when the overlay closes. The model is mmapped,
 * so a second summon within a short window is cheap even after the handle is freed —
 * the pages are still in the file cache.
 */
class LlamaEngine(private val context: Context) {

    @Volatile
    private var handle: Long = 0

    /** Where this model's prompt cache lives; set when the model is loaded. */
    private var cacheFile: File? = null

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(modelFile: File, contextSize: Int = DEFAULT_CONTEXT): Boolean =
        withContext(Dispatchers.Default) {
            if (!NativeBridge.load(context)) return@withContext false
            if (!modelFile.isFile) {
                Log.w(TAG, "llm model missing: ${modelFile.absolutePath}")
                return@withContext false
            }
            unload()
            handle = LlamaNative.nativeLoad(modelFile.absolutePath, contextSize, threadCount())
            if (handle != 0L) {
                val cache = File(modelFile.parentFile, modelFile.name + CACHE_SUFFIX)
                cacheFile = cache
                if (PromptCacheGuard.isFresh(readStamp(cache), installStamp())) {
                    LlamaNative.nativeLoadState(handle, cache.absolutePath)
                } else {
                    // Written by a different build, whose baked-in prompt may not match this
                    // one's. A mismatched cache does not fail to load, it corrupts
                    // generation: on device it made the router copy its first example and
                    // answer almost everything with "set an alarm". Drop it and prefill once.
                    cache.delete()
                    stampFile(cache).delete()
                }
            }
            handle != 0L
        }

    /**
     * Writes the current prompt cache to disk, so the next session skips its prefill.
     *
     * Called when a session ends rather than after each reply: the contents would be
     * identical, and the write is several megabytes.
     */
    suspend fun saveState(): Boolean = withContext(Dispatchers.Default) {
        val current = handle
        val file = cacheFile
        current != 0L && file != null && synchronized(this@LlamaEngine) {
            handle != 0L && LlamaNative.nativeSaveState(handle, file.absolutePath).also { ok ->
                // Stamped with this install, so a later build cannot reuse it.
                if (ok) runCatching { stampFile(file).writeText(installStamp()) }
            }
        }
    }

    private fun stampFile(cache: File) = File(cache.parentFile, cache.name + STAMP_SUFFIX)

    private fun readStamp(cache: File): String? = runCatching { stampFile(cache).readText() }.getOrNull()

    // The package's install/update time - the one signal that the baked-in prompt may have
    // moved, since the prompt ships inside the APK.
    private fun installStamp(): String = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .lastUpdateTime.toString()
    }.getOrDefault("")

    /** Model name, parameter count and context size, for the diagnostics screen. */
    suspend fun describe(): String = withContext(Dispatchers.Default) {
        synchronized(this@LlamaEngine) { if (handle == 0L) "" else LlamaNative.nativeDescribe(handle) }
    }

    /**
     * Generates a completion, optionally constrained by a GBNF grammar.
     *
     * With a grammar the output is valid by construction, so callers do not need a
     * retry loop for malformed output — there is no such state to recover from.
     *
     * [stopAt] ends generation as soon as the output contains it, for a caller that
     * will discard everything after that point anyway.
     */
    suspend fun generate(
        prompt: String,
        grammar: String = "",
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        stopAt: String = "",
    ): Completion = withContext(Dispatchers.Default) {
        val current = handle
        if (current == 0L) return@withContext Completion("", 0)
        val startedAt = System.nanoTime()
        val text = synchronized(this@LlamaEngine) {
            if (handle == 0L) "" else LlamaNative.nativeGenerate(handle, prompt, grammar, maxTokens, stopAt)
        }
        Completion(text.trim(), (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
    }

    /** True if llama.cpp accepts this GBNF. */
    suspend fun validateGrammar(grammar: String): Boolean = withContext(Dispatchers.Default) {
        val current = handle
        current != 0L && synchronized(this@LlamaEngine) {
            handle != 0L && LlamaNative.nativeValidateGrammar(handle, grammar)
        }
    }

    /**
     * Whether [grammar] would allow the model to produce [text].
     *
     * Distinct from [validateGrammar], which only asks whether the grammar parses. A
     * grammar that parses can still be wrong in the way that matters.
     */
    suspend fun grammarAccepts(grammar: String, text: String): Boolean =
        withContext(Dispatchers.Default) {
            val current = handle
            current != 0L && synchronized(this@LlamaEngine) {
                handle != 0L && LlamaNative.nativeGrammarAccepts(handle, grammar, text)
            }
        }

    /**
     * Every native call above runs under this object's monitor and re-reads the handle
     * inside it, so unload waits for a call in flight instead of freeing the context
     * under it. Dismissing the overlay mid-reply used to do exactly that.
     */
    @Synchronized
    fun unload() {
        if (handle != 0L) {
            LlamaNative.nativeFree(handle)
            handle = 0
        }
    }

    /** Leaves headroom for the UI; saturating every core makes the interface stutter. */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, MAX_THREADS)

    companion object {
        private const val TAG = "TheMachine"
        private const val NANOS_PER_MILLI = 1_000_000
        private const val MAX_THREADS = 6

        /** Enough for the tool list, the user's context files and one command. */
        const val DEFAULT_CONTEXT = 2048

        /** Appended to the model's own file name, so a cache follows its model. */
        const val CACHE_SUFFIX = ".prompt-cache"

        /** Appended to the cache file's name; records which build wrote it. */
        const val STAMP_SUFFIX = ".build"

        /**
         * The longest legal tool call is well under this. The cap is what bounds the
         * worst case when the model would otherwise keep emitting optional arguments.
         */
        const val DEFAULT_MAX_TOKENS = 80
    }
}
