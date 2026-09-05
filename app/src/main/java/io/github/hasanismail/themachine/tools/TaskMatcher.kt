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

import io.github.hasanismail.themachine.context.TaskLine

/**
 * Finds the task a spoken phrase refers to.
 *
 * Nobody says a task back the way they wrote it. "Take the bins out" is asked for as "the
 * bins", "bins out", or "that bin thing", so an exact match finds almost nothing. Equally,
 * marking the wrong task done is a silent loss — the user believes something is handled
 * that is not — so a weak match must be reported as ambiguous rather than guessed at.
 *
 * Scored on overlapping words rather than edit distance: speech recognition changes whole
 * words ("bins" for "bin") far more often than it changes letters within one.
 */
object TaskMatcher {

    private val WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Words too common to identify anything, so they neither help nor hurt a match. */
    private val NOISE = setOf(
        "a", "an", "the", "to", "my", "me", "that", "this", "it", "for", "of", "and",
        "task", "reminder", "thing", "please", "off", "out",
    )

    /** What a lookup found. */
    sealed interface Result {
        data class One(val task: TaskLine) : Result
        data class Several(val tasks: List<TaskLine>) : Result
        data object None : Result
    }

    /**
     * Matches [spoken] against the tasks still outstanding.
     *
     * A phrase that names one task clearly wins outright. Where two score equally it is
     * reported as ambiguous, because picking one at random is how the wrong thing gets
     * ticked off.
     */
    fun find(spoken: String, tasks: List<TaskLine>): Result {
        val wanted = meaningful(spoken)
        if (wanted.isEmpty()) return Result.None
        val open = tasks.filterNot { it.done }
        if (open.isEmpty()) return Result.None

        val scored = open
            .map { it to score(wanted, meaningful(it.title)) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
        if (scored.isEmpty()) return Result.None

        val best = scored.first().second
        val leaders = scored.filter { (_, score) -> score == best }.map { (task, _) -> task }
        return if (leaders.size == 1) Result.One(leaders.first()) else Result.Several(leaders)
    }

    private fun meaningful(text: String): Set<String> =
        text.lowercase().split(WORD).filter { it.length > 1 && it !in NOISE }.toSet()

    /**
     * How well the spoken words cover the task's words.
     *
     * Deliberately counts matched words rather than a ratio: "call the dentist about the
     * appointment" said as "dentist" should still find it, and a ratio would punish the
     * task for being wordier than the phrase used to recall it.
     */
    private fun score(spoken: Set<String>, title: Set<String>): Int =
        spoken.count { word -> title.any { it == word || it.startsWith(word) || word.startsWith(it) } }
}
