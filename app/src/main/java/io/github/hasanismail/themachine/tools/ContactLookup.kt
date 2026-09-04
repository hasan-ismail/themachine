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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** A contact that matched, carrying the number so a chosen option can be acted on. */
data class Contact(val name: String, val number: String)

/** The outcome of resolving a spoken name to a number. */
sealed interface ContactResolution {
    /** Exactly one contact — or a literal number, or the owner — matched. */
    data class One(val number: String) : ContactResolution

    /** Several distinct contacts matched; the caller must let the user choose, not guess. */
    data class Many(val options: List<Contact>) : ContactResolution

    /** Nothing matched, or there was no way to resolve. */
    data object None : ContactResolution
}

/**
 * Turns a spoken name into a phone number.
 *
 * If the name is already a number it is used as-is, so messaging works even without
 * contacts access. "me" resolves to the phone's own owner, never a contact. Otherwise it
 * queries the contacts provider — but the provider's filter is loose (it returned "MI
 * Aziz" for "me"), so a candidate is only accepted when its name genuinely matches. A
 * message sent to the wrong person is the worst outcome here; "not found" is far better.
 */
class ContactLookup(
    private val context: Context,
    /** The owner's number as they saved it in settings; null until they have. */
    private val ownNumber: () -> String? = { null },
) {

    /** The number to use, or an ambiguity/absence the caller must handle — never a guess. */
    fun resolve(spoken: String): ContactResolution {
        val trimmed = spoken.trim()
        return when {
            trimmed.isEmpty() -> ContactResolution.None

            looksLikeNumber(trimmed) -> ContactResolution.One(digitsOf(trimmed))

            // "me" is the number the owner typed in; the contacts Profile is only a fallback.
            ContactMatch.isSelf(trimmed) ->
                (savedOwnNumber() ?: profileNumberIfAllowed())?.let(ContactResolution::One)
                    ?: ContactResolution.None

            !hasContactsPermission() -> ContactResolution.None

            else -> lookupByName(trimmed)
        }
    }

    /** The single unambiguous number, or null when absent OR ambiguous. Used by dry probes. */
    fun resolveNumber(spoken: String): String? = (resolve(spoken) as? ContactResolution.One)?.number

    private fun digitsOf(number: String): String = number.filter { it.isDigit() || it == '+' }

    private fun savedOwnNumber(): String? = ownNumber()?.let(::digitsOf)?.takeIf { it.length >= MIN_DIGITS }

    private fun profileNumberIfAllowed(): String? = if (hasContactsPermission()) profileNumber() else null

    /**
     * Every distinct contact whose display name genuinely matches the spoken name, deduped
     * by person. One resolves; several is an ambiguity to hand back for the user to choose
     * — "Hassan" matched both "Hassan" and "Dr Hassan ER Chicago", and a blind pick sent a
     * message to the wrong one. The query is driven by the most distinctive word (the
     * longest — a surname beats a common first name); the full-name rule filters the rows.
     */
    private fun lookupByName(name: String): ContactResolution {
        val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val byContact = LinkedHashMap<Long, Contact>()
        for (key in words.sortedByDescending { it.length }) {
            collectMatches(key, name, byContact)
        }
        // Prefer an exact whole-name match: "Hasan" should reach the contact named exactly
        // "Hasan", not offer every "Hasan Something" — a common first name matched fourteen
        // people. Only when no single exact match exists is it a real choice for the user.
        val exact = byContact.values.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val matches = exact.ifEmpty { byContact.values.toList() }
        return when (matches.size) {
            0 -> ContactResolution.None
            1 -> ContactResolution.One(matches.first().number)
            else -> ContactResolution.Many(matches.take(MAX_OPTIONS))
        }
    }

    private fun collectMatches(filterKey: String, name: String, into: MutableMap<Long, Contact>) {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(filterKey),
        )
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(0)
                val number = cursor.getString(1) ?: continue
                val display = cursor.getString(2).orEmpty()
                // First number per person; a second number for someone already matched is
                // the same person, not a new ambiguity.
                if (ContactMatch.matches(name, display) && !into.containsKey(contactId)) {
                    into[contactId] = Contact(display, number)
                }
            }
        }
    }

    /** The device owner's number from the contacts Profile — usually unset, hence the setting. */
    private fun profileNumber(): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.Profile.CONTENT_URI,
            ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun looksLikeNumber(value: String): Boolean =
        value.count { it.isDigit() } >= MIN_DIGITS && value.none { it.isLetter() }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Short enough for a shortcode, long enough not to match "7" in "call 7". */
        const val MIN_DIGITS = 3

        /** A selector longer than this is unusable; the user says a fuller name instead. */
        const val MAX_OPTIONS = 6
        val WHITESPACE = Regex("\\s+")
    }
}
