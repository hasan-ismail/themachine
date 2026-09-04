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
 * Whether a spoken name is a genuine match for a contact's display name.
 *
 * The contacts provider's filter is loose — asked for "me" it returned "MI Aziz", and a
 * message went to a father-in-law at 3am. Texting the wrong person is the worst thing this
 * feature can do, so a match must be earned: the spoken name has to be a whole word of the
 * display name (a first name, a last name, a nickname), or the display name itself — not a
 * substring that happened to overlap.
 */
object ContactMatch {

    private val SEP = Regex("[^\\p{L}\\p{N}]+")

    /** True if [spoken] names the contact called [display]. */
    fun matches(spoken: String, display: String): Boolean {
        val query = spoken.lowercase().trim()
        if (query.isEmpty() || display.isBlank()) return false
        val name = display.lowercase().trim()
        if (name == query) return true
        val words = name.split(SEP).filter { it.isNotEmpty() }
        // Every spoken word must be one of the contact's name words: "osman" matches
        // "Osman Khan", "john smith" matches "John Smith", but "me" does not match "MI Aziz".
        val queryWords = query.split(SEP).filter { it.isNotEmpty() }
        return queryWords.isNotEmpty() && queryWords.all { it in words }
    }

    /** Names for the user themselves, which must resolve to their own number, not a contact. */
    fun isSelf(spoken: String): Boolean =
        spoken.lowercase().trim() in setOf("me", "myself", "my number", "my phone", "my cell")
}
