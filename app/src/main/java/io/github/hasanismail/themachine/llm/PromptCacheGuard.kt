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

/**
 * Decides whether a saved prompt-cache may be reused.
 *
 * The prompt — the tool list, the examples, the answer instructions — is baked into the
 * APK. A cache is the model's key/value state for that prompt's leading tokens, and
 * loading one written for a *different* prompt corrupts generation: on device it made
 * the router copy its first example and answer almost everything with "set an alarm".
 *
 * So a cache is tied to the build that wrote it. Any reinstall or update changes the
 * package's install time, which is the one signal that the prompt may have moved, and
 * the cache is dropped rather than trusted. Same build, same prompt, cache reused.
 */
object PromptCacheGuard {

    /** True only when the cache was written by exactly this install. */
    fun isFresh(storedStamp: String?, currentStamp: String): Boolean =
        currentStamp.isNotEmpty() && storedStamp == currentStamp
}
