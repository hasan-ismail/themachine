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

/**
 * Recognises "so what have I got on?" without asking a language model.
 *
 * Reading back the list is the one reminder operation with no argument to get wrong: there
 * is nothing to mis-hear, nothing to match, and exactly one possible answer. It was still
 * the operation the model got wrong most — "what do I have to do" came back as
 * `unsupported` — because a tool declared for it is one more line diluting a 1B that
 * measurably degrades with every tool added. Declaring it cost accuracy on timers and
 * reminders; recognising it here costs nothing at all.
 *
 * Held to a narrow set of phrasings on purpose. This is not a parser and must not become
 * one: anything it does not recognise falls through to the model exactly as before, which
 * is the same discipline [LocalAnswers] keeps.
 */
object TaskQuestion {

    private val WORD = Regex("""[^a-z]+""")

    /** Words that make a phrase a request rather than a statement. */
    private val ASKING = setOf("what", "whats", "which", "any", "list", "read", "tell", "do", "have", "got")

    /** What the list is called out loud. */
    private val SUBJECT = setOf("reminders", "reminder", "tasks", "task", "list", "todo", "todos")

    /** Words that mean a *new* item, not a reading of the existing ones. */
    private val CREATING = setOf("remind", "add", "new", "set", "make", "create")

    /** Words that say a task is finished. */
    private val FINISHED = setOf("done", "finished", "complete", "completed", "did")

    /** Words wrapped around the task's name, which are not part of it. */
    private val PLUMBING = setOf(
        "mark", "tick", "cross", "check", "off", "as", "the", "a", "an", "my", "that",
        "this", "it", "i", "ive", "have", "already", "task", "reminder", "please",
    )

    /**
     * The task named as finished, or null if this is not someone ticking something off.
     *
     * Returned as words rather than a boolean because the subject matters: "mark the bins
     * done" has to reach [io.github.hasanismail.themachine.tools.TaskMatcher] with "bins",
     * not with the whole sentence.
     *
     * Handled here rather than by a tool for the reason the list is: declaring one sent
     * "mark the bins done" to create_reminder, which files a *second* copy of the task
     * instead of ticking off the first — a silent, actively wrong outcome.
     */
    fun completionTarget(transcript: String): String? {
        val words = transcript.lowercase().split(WORD).filter { it.isNotEmpty() }
        if (words.none { it in FINISHED }) return null
        // "Remind me to mark the report done" is an instruction to write something down,
        // and it contains every finishing word there is.
        if (words.any { it in CREATING }) return null

        val subject = words.filterNot { it in FINISHED || it in PLUMBING }
        return subject.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** True if [transcript] is asking for the list to be read back. */
    fun isAskingForList(transcript: String): Boolean {
        val words = transcript.lowercase().split(WORD).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // "Remind me to buy milk" mentions reminders and is emphatically not a request to
        // read the list; the creating words settle that before anything else is weighed.
        if (words.any { it in CREATING }) return false

        val asks = words.any { it in ASKING }
        val names = words.any { it in SUBJECT }
        if (asks && names) return true

        // "What do I have to do" names no subject at all, and is the single commonest way
        // to ask. Matched as a whole phrase rather than by adding "do" to the subjects,
        // which would drag in half of ordinary speech.
        val joined = words.joinToString(" ")
        return WHOLE_PHRASES.any { joined.contains(it) }
    }

    private val WHOLE_PHRASES = listOf(
        "what do i have to do",
        "what have i got to do",
        "what do i need to do",
        "what am i supposed to do",
        "whats on my plate",
    )
}
