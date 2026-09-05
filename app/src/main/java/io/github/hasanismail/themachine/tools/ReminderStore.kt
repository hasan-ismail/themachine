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

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.hasanismail.themachine.context.MachineFiles
import io.github.hasanismail.themachine.context.TaskLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tasks the assistant has been asked to remember, and the alarms that fire them.
 *
 * Stored as Markdown rather than in a database, because the same file is read back
 * into the model's prompt as context and is meant to be readable and editable by the
 * user directly. A row in a Room table would be neither.
 *
 * The file is the record; the alarms are derived from it. Alarms do not survive a reboot
 * or a force-stop and the file does, so [rescheduleAll] can always rebuild them.
 */
class ReminderStore(private val context: Context) {

    private val files = MachineFiles(context)

    /** Whether an alarm landed exactly when asked, or as close as the phone would allow. */
    enum class Exactness { EXACT, INEXACT }

    /**
     * Records a task, and schedules an alarm if a time was given.
     *
     * A time in the past rolls forward rather than firing immediately: someone saying
     * "remind me at 7" at 9pm means tomorrow morning, and an alarm that goes off the
     * instant they finish speaking is the least useful possible reading.
     */
    suspend fun create(
        task: String,
        hour: Int?,
        minute: Int,
        tomorrow: Boolean,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (hour == null) {
            files.appendTask(task, due = null)
            return@withContext ToolResult.ok("I will remember: $task.")
        }
        if (hour !in 0..HOUR_MAX || minute !in 0..MINUTE_MAX) {
            return@withContext ToolResult.failed("That is not a time I can use.")
        }

        val now = LocalDateTime.now()
        var due = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (tomorrow || due.isBefore(now)) due = due.plusDays(1)

        val id = files.appendTask(task, due)
        val exactness = schedule(id, task, due)
            ?: return@withContext ToolResult.failed("I have written that down, but I cannot reach the alarm service.")

        val spokenTime = due.format(SPOKEN_TIME)
        when {
            // Promising a reminder that can never be shown would be a lie; say so now.
            !notificationsWillShow() -> ToolResult.ok(
                "I will remind you to $task at $spokenTime, but notifications are off.",
                "Allow Notifications under System access, or the reminder will be silent.",
            )

            exactness == Exactness.INEXACT -> ToolResult.ok(
                "I will remind you to $task at about $spokenTime.",
                "Grant Exact alarms under System access for it to be on time.",
            )

            else -> ToolResult.ok("I will remind you to $task at $spokenTime.")
        }
    }

    /**
     * Arms the alarm for one task. Null only if the phone has no alarm service at all.
     *
     * Exact when permitted, otherwise as close as Android allows: a reminder a few
     * minutes late is a great deal better than a reminder that was written down and
     * never fired.
     */
    fun schedule(id: String, task: String, due: LocalDateTime): Exactness? {
        val alarms = context.getSystemService<AlarmManager>() ?: return null
        val whenMillis = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = pendingIntentFor(id, task)
        return try {
            if (alarms.canScheduleExactAlarms()) {
                // AllowWhileIdle so Doze cannot quietly defer a reminder the user set
                // deliberately — that is the entire point of an exact alarm.
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
                Exactness.EXACT
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
                Exactness.INEXACT
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, falling back", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
            Exactness.INEXACT
        }
    }

    /** Pushes a task ten minutes out and re-arms it; returns the new due time. */
    fun snooze(id: String, task: String, minutes: Long = SNOOZE_MINUTES): LocalDateTime {
        val due = LocalDateTime.now().plusMinutes(minutes).withSecond(0).withNano(0)
        files.rescheduleTask(id, due)
        schedule(id, task, due)
        return due
    }

    /** Ticks the task off and disarms its alarm. */
    fun complete(id: String) {
        files.completeTask(id)
        context.getSystemService<AlarmManager>()?.cancel(pendingIntentFor(id, task = ""))
    }

    /** Every task still outstanding, soonest first, undated ones last. */
    fun open(): List<TaskLine> = files.readTasks()
        .filterNot { it.done }
        .sortedWith(compareBy(nullsLast()) { it.due })

    /**
     * Ticks off whatever [spoken] names, or says why it could not.
     *
     * Ambiguity is reported rather than resolved: ticking off the wrong task is a silent
     * loss, because the user then believes something is handled that is not.
     */
    fun completeMatching(spoken: String): ToolResult =
        when (val found = TaskMatcher.find(spoken, files.readTasks())) {
            is TaskMatcher.Result.One -> {
                val id = found.task.id
                if (id == null) {
                    ToolResult.failed("I can only tick off tasks I set myself.")
                } else {
                    complete(id)
                    ToolResult.ok("Done: ${found.task.title}.")
                }
            }

            is TaskMatcher.Result.Several -> ambiguous(found.tasks)

            TaskMatcher.Result.None -> ToolResult.failed("I could not find that on your list.")
        }

    /** Moves whatever [spoken] names to [due], re-arming its alarm. */
    fun rescheduleMatching(spoken: String, due: LocalDateTime): ToolResult =
        when (val found = TaskMatcher.find(spoken, files.readTasks())) {
            is TaskMatcher.Result.One -> {
                val id = found.task.id
                if (id == null) {
                    ToolResult.failed("I can only move tasks I set myself.")
                } else {
                    files.rescheduleTask(id, due)
                    schedule(id, found.task.title, due)
                    ToolResult.ok("Moved ${found.task.title} to ${due.format(SPOKEN_TIME)}.")
                }
            }

            is TaskMatcher.Result.Several -> ambiguous(found.tasks)

            TaskMatcher.Result.None -> ToolResult.failed("I could not find that on your list.")
        }

    private fun ambiguous(tasks: List<TaskLine>): ToolResult =
        ToolResult.failed("I have more than one of those: ${tasks.joinToString(", ") { it.title }}.")

    /**
     * Re-arms every open, dated task from the file. Returns how many.
     *
     * A reminder whose time passed while the phone was off fires shortly after it comes
     * back rather than being lost: late is recoverable, silent is not.
     */
    fun rescheduleAll(): Int {
        val now = LocalDateTime.now()
        val armed = files.readTasks()
            .filter { !it.done && it.id != null && it.due != null }
            .count { task ->
                val due = task.due ?: return@count false
                val at = if (due.isBefore(now)) now.plusSeconds(LATE_GRACE_SECONDS) else due
                schedule(task.id ?: return@count false, task.title, at) != null
            }
        Log.i(TAG, "reminders: re-armed $armed")
        return armed
    }

    /**
     * The one PendingIntent an alarm is set and cancelled with.
     *
     * Identity comes from the id, in both the request code and the data URI. It used to
     * come from the due time, so two reminders for the same minute were the same intent
     * and the second silently cancelled the first.
     */
    private fun pendingIntentFor(id: String, task: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            data = "themachine://task/$id".toUri()
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_TASK, task)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Arms the next repeat of a reminder that has not been dealt with.
     *
     * Deliberately a separate alarm from [schedule]: it must not touch the task's due time
     * in the file, or asking again would keep pushing the task's own deadline forward and
     * the list would show a time that has nothing to do with when it was set.
     */
    fun armNag(id: String, task: String, nag: Int) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        val at = System.currentTimeMillis() + NAG_MINUTES * MILLIS_PER_MINUTE
        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FIRE
                data = "themachine://task/$id".toUri()
                putExtra(ReminderReceiver.EXTRA_ID, id)
                putExtra(ReminderReceiver.EXTRA_TASK, task)
                putExtra(ReminderReceiver.EXTRA_NAG, nag)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            .onFailure {
                // Without the exact-alarm permission this is still worth doing inexactly:
                // a repeat a few minutes late is the point, a repeat that never comes is not.
                runCatching { alarms.set(AlarmManager.RTC_WAKEUP, at, pending) }
            }
        Log.i(TAG, "reminder $id asking again in $NAG_MINUTES min (repeat $nag)")
    }

    /**
     * Whether a reminder would actually appear.
     *
     * The app-wide switch is not the only one: long-pressing a delivered reminder and
     * choosing "turn off notifications" silences the Reminders channel while leaving the
     * app enabled, and a reminder promised under that setting cannot be kept.
     */
    private fun notificationsWillShow(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(ReminderReceiver.CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    // Not private: the executor speaks times in the same format and reuses the same snooze
    // length, so the two must not drift apart.
    companion object {
        const val TAG = "TheMachine"
        const val HOUR_MAX = 23
        const val MINUTE_MAX = 59
        const val SNOOZE_MINUTES = 10L

        /** How long between repeats of a reminder nobody has answered. */
        const val NAG_MINUTES = 5L

        const val MILLIS_PER_MINUTE = 60_000L
        const val LATE_GRACE_SECONDS = 5L
        val SPOKEN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
