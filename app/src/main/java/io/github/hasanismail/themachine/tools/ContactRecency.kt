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

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony

/**
 * Orders contacts by how recently they were actually reached.
 *
 * The contacts provider's own recency and frequency fields were zeroed by the platform
 * years ago (every contact reads 0), so the real signal is the SMS and call logs: the person
 * you last texted or called is the one you most likely mean now. Used to put the likeliest
 * option first — and, capped lists being what they are, to keep it on screen — when a spoken
 * name matches several people. Reads only the dates; without the SMS / call-log permissions
 * it degrades to the order it was given.
 */
class ContactRecency(private val context: Context) {

    /** [options] newest-first by last contact; never-reached ones keep their order, at the end. */
    fun sortByRecency(options: List<Contact>): List<Contact> {
        if (options.size < 2) return options
        val lastReached = options.associate { it.number to lastReached(it.number) }
        // sortedByDescending is stable, so 0 (never reached) preserves the incoming order.
        return options.sortedByDescending { lastReached[it.number] ?: 0L }
    }

    private fun lastReached(number: String): Long {
        val suffix = number.filter { it.isDigit() }.takeLast(MATCH_DIGITS)
        if (suffix.length < MATCH_DIGITS) return 0L
        val texted = latestDate(Telephony.Sms.CONTENT_URI, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, suffix)
        val called = latestDate(CallLog.Calls.CONTENT_URI, CallLog.Calls.NUMBER, CallLog.Calls.DATE, suffix)
        return maxOf(texted, called)
    }

    /**
     * The newest date in [uri] whose number ends in [suffix]. Matching by the last digits
     * rather than the whole number sidesteps formatting: "+1 555-010-1234" and "15550101234"
     * are the same line. A SecurityException (permission not granted) is caught as "unknown".
     */
    private fun latestDate(uri: Uri, numberColumn: String, dateColumn: String, suffix: String): Long =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(dateColumn),
                "$numberColumn LIKE ?",
                arrayOf("%$suffix"),
                "$dateColumn DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }.getOrNull() ?: 0L

    private companion object {
        /** Enough digits to identify a line without tripping on formatting differences. */
        const val MATCH_DIGITS = 7
    }
}
