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

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Questions the phone can answer exactly, without asking a language model.
 *
 * Deliberately a very short list. The time and the date are things the device knows to
 * the second and a model only knows because they were written into its prompt — and the
 * 1B, asked what time it was, answered by reading out the notification shade. Answering
 * these from the clock is both instant and incapable of being wrong, which no amount of
 * prompting can promise.
 *
 * Everything else goes to the model. This is not a general rules engine and must not
 * grow into one.
 */
object LocalAnswers {

    private val TIME = DateTimeFormatter.ofPattern("h:mm a")
    private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM")

    private val CLOCK_WORDS = setOf("time", "clock", "oclock")
    private val DATE_WORDS = setOf("date", "day", "today", "month", "year")
    private val ASKING = setOf(
        "what", "whats", "which", "tell", "say", "is", "it", "the", "and", "me", "now", "s", "in", "at",
    )

    /**
     * Time zones by the city that names them, e.g. "berlin" -> Europe/Berlin.
     *
     * Built from the platform's own zone table rather than a list written here, so it
     * covers every city Android knows and needs no maintenance. Ambiguous last segments
     * (several zones ending "GMT") are dropped rather than guessed at.
     */
    private val ZONES_BY_CITY: Map<String, ZoneId> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') }
            .groupBy { it.substringAfterLast('/').replace('_', ' ').lowercase() }
            .filterValues { it.size == 1 }
            .mapValues { (_, ids) -> ZoneId.of(ids.first()) }
    }

    /** The time in a named place, or null when the words name no place this phone knows. */
    private fun elsewhere(place: List<String>, words: List<String>, now: LocalDateTime): String? {
        if (words.none { it in CLOCK_WORDS }) return null
        val zone = ZONES_BY_CITY[place.joinToString(" ")] ?: return null
        val there = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone)
        val named = place.joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
        return "It is ${there.format(TIME)} in $named."
    }

    /** The answer, or null if this is not a question the clock can settle. */
    fun of(transcript: String, now: LocalDateTime = LocalDateTime.now()): String? {
        // Split on anything that is not a letter, so "what's" becomes "what" and "s"
        // rather than a word that matches nothing.
        val words = transcript.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
        // A question, not a command: "set a timer" mentions no clock word, and "what
        // time is it" is nothing but asking words and a clock word.
        if (words.none { it in ASKING }) return null

        // Anything left over after the asking and clock words is a place, if it is one the
        // platform's zone table knows. "What time is it in Berlin" is as exactly knowable
        // as "what time is it" — the phone holds the whole zone database — and routing it
        // to a 1B produced read_screen, which recited the notification shade instead.
        val leftover = words.filterNot { it in ASKING || it in CLOCK_WORDS || it in DATE_WORDS }
        if (leftover.isNotEmpty()) return elsewhere(leftover, words, now)

        val wantsClock = words.any { it in CLOCK_WORDS }
        val wantsDate = words.any { it in DATE_WORDS }
        return when {
            wantsClock && wantsDate -> "It is ${now.format(TIME)} on ${now.format(DATE)}."
            wantsClock -> "It is ${now.format(TIME)}."
            wantsDate -> "It is ${now.format(DATE)}."
            else -> null
        }
    }
}
