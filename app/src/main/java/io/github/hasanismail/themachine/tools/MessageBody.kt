/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

/**
 * Guards against a fabricated message body.
 *
 * A message body is an unconstrained string, and asked to text someone with no words to
 * send, the model fills the slot anyway — "hello how are you" for a bare "text Dad". A
 * text is one of the few actions with no undo, so an invented body must never be sent.
 * The words of a real message are the words the user actually said; a body whose words
 * are mostly absent from the transcript was not dictated, it was invented.
 */
object MessageBody {

    /** Below this fraction of body words found in the transcript, the body was invented. */
    private const val TRACEABLE_FRACTION = 0.5f

    private val WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * True if [body] looks like something the user actually said in [transcript].
     *
     * A body of only tiny tokens ("ok", "yes", a smiley) has nothing to fabricate and is
     * allowed through. Otherwise at least half its real words must appear in the
     * transcript — enough to pass an ordinary paraphrase ("I'm" heard as "I am") while
     * catching a body the model wrote out of nothing.
     */
    fun isTraceable(transcript: String, body: String): Boolean {
        val haystack = transcript.lowercase()
        val words = body.lowercase().split(WORD).filter { it.length >= 2 }
        if (words.isEmpty()) return true
        val found = words.count { haystack.contains(it) }
        return found.toFloat() / words.size >= TRACEABLE_FRACTION
    }
}
