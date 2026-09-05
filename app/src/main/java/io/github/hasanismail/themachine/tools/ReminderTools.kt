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

import java.time.LocalDateTime

/**
 * The spoken half of the reminder list: reading it back, ticking things off, moving them.
 *
 * Separate from [ToolExecutor] because the executor is already at the size where one more
 * responsibility makes it the place everything lands, and these three share a subject —
 * a task the user names in their own words, resolved by [TaskMatcher].
 */
class ReminderTools(private val reminders: ReminderStore) {

    /**
     * Reads the list back, in the order it will actually happen.
     *
     * Capped, because a list read aloud past about five items stops being information and
     * becomes something to sit through. The remainder is offered as a count so nothing is
     * silently hidden.
     */
    fun list(): ToolResult {
        val open = reminders.open()
        if (open.isEmpty()) return ToolResult.ok("Nothing on your list.")
        val spoken = open.take(SPOKEN_TASKS).joinToString(". ") { task ->
            val due = task.due
            if (due == null) task.title else "${task.title}, ${due.format(ReminderStore.SPOKEN_TIME)}"
        }
        val rest = open.size - SPOKEN_TASKS
        val tail = if (rest > 0) " And $rest more." else ""
        return ToolResult.ok("$spoken.$tail", open.joinToString("\n") { it.title })
    }

    fun complete(call: ToolCall): ToolResult {
        val task = call.string("task") ?: return ToolResult.failed("Which one?")
        return reminders.completeMatching(task)
    }

    /**
     * Moves a task, either to a clock time or by a delay.
     *
     * With neither given it falls back to the same ten minutes the notification's Snooze
     * button uses, because "remind me again" with no time at all is the commonest way to
     * ask and refusing it would be pedantic.
     */
    fun reschedule(call: ToolCall): ToolResult {
        val task = call.string("task") ?: return ToolResult.failed("Which one?")
        val delay = call.int("minutes_from_now")
        val hour = call.int("hour")
        val now = LocalDateTime.now()
        val due = when {
            delay != null && delay > 0 -> now.plusMinutes(delay.toLong())

            hour != null -> {
                // The same rolling-forward rule create uses: a time already past today
                // means tomorrow, or nothing could be moved to "seven" in the evening.
                val at = now.withHour(hour).withMinute(call.int("minute") ?: 0)
                if (call.bool("tomorrow") || at.isBefore(now)) at.plusDays(1) else at
            }

            else -> now.plusMinutes(ReminderStore.SNOOZE_MINUTES)
        }.withSecond(0).withNano(0)
        return reminders.rescheduleMatching(task, due)
    }

    private companion object {
        /** How many tasks are worth reading out before a count is kinder than a list. */
        const val SPOKEN_TASKS = 5
    }
}
