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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.hasanismail.themachine.R
import io.github.hasanismail.themachine.context.MachineFiles
import io.github.hasanismail.themachine.ui.MainActivity
import java.io.IOException

/**
 * Shows a reminder when its alarm comes due, and handles the two things a person can do
 * with it from the notification: tick it off, or push it back ten minutes.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val task = intent.getStringExtra(EXTRA_TASK) ?: ""
        when (intent.action) {
            ACTION_DONE -> offMainThread {
                ReminderStore(context).complete(id)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            }

            ACTION_SNOOZE -> offMainThread {
                val due = ReminderStore(context).snooze(id, task)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                Log.i(TAG, "reminder snoozed to $due")
            }

            else -> {
                // One offMainThread for the whole branch. goAsync() hands out a single
                // PendingResult per dispatch and returns null the second time, so calling
                // it twice here crashed the process on a null finish().
                val nag = intent.getIntExtra(EXTRA_NAG, 0)
                offMainThread { keepAsking(context, id, task, nag) }
            }
        }
    }

    /**
     * Shows the reminder, and arranges to ask again unless it has been dealt with.
     *
     * A reminder that fires once and gives up is a reminder you can miss by being in
     * another room. So it asks again every few minutes — but every repeat re-reads the
     * task file first, and stops the moment the task is ticked off, deleted, or moved to
     * a new time. That check is what keeps this from becoming the re-arm loop this file
     * has already produced once: the stopping condition is the user's own list, not a
     * counter this code keeps.
     *
     * It also gives up after [MAX_NAGS]. Something ignored eight times is not going to be
     * answered by a ninth, and a notification that can never be silenced is a worse
     * failure than one that is missed.
     */
    private fun keepAsking(context: Context, id: String, task: String, nag: Int) {
        val files = MachineFiles(context)
        // Recorded as delivered before it is shown, so a process start caused by this very
        // alarm does not find it overdue and arm it all over again.
        files.markDelivered(id)
        val still = files.readTasks().firstOrNull { it.id == id }
        if (still == null || still.done) {
            Log.i(TAG, "reminder $id settled; not asking again")
            NotificationManagerCompat.from(context).cancel(id.hashCode())
            return
        }
        show(context, id, task, nag)
        if (nag < MAX_NAGS) ReminderStore(context).armNag(id, task, nag + 1)
    }

    /**
     * File rewrites belong off the main thread, and a receiver is torn down as soon as
     * onReceive returns unless it says otherwise.
     */
    private fun offMainThread(work: () -> Unit) {
        // Nullable in practice: goAsync() hands out one PendingResult per dispatch and
        // returns null if asked again, or when a receiver is invoked outside one.
        val pending: PendingResult? = goAsync()
        Thread {
            try {
                work()
            } catch (e: IOException) {
                // A full disk while ticking a task off should lose the tick, not the
                // process: this runs on a bare thread, where an escape is fatal.
                Log.e(TAG, "reminder action failed", e)
            } finally {
                pending?.finish()
            }
        }.start()
    }

    private fun show(context: Context, id: String, task: String, nag: Int = 0) {
        // Blocked notifications are dropped by the platform without a word; the store
        // warns at creation time, and this is the second chance to leave a trace.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "reminder due but notifications are off: $task")
            return
        }
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Says which time this is, so a repeat reads as the same reminder asking again
            // rather than as a new one the user has not seen.
            .setContentTitle(if (nag == 0) "Reminder" else "Still waiting")
            .setContentText(task)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .addAction(0, "Done", action(context, id, task, ACTION_DONE, SALT_DONE))
            .addAction(0, "Snooze 10 min", action(context, id, task, ACTION_SNOOZE, SALT_SNOOZE))
            // Not auto-cancelled and not dismissible by a swipe. The whole point is that it
            // outlasts being glanced at: it goes when the task is done or snoozed, which
            // are both one tap away, and it gives up on its own after MAX_NAGS.
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .build()

        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    /** A distinct PendingIntent per button: same receiver, different action and data. */
    private fun action(context: Context, id: String, task: String, action: String, salt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode() * SALT_STRIDE + salt,
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                data = "themachine://task/$id/$salt".toUri()
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TASK, task)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when something you asked to be reminded of comes due."
            },
        )
    }

    companion object {
        private const val TAG = "TheMachine"
        const val EXTRA_ID = "id"
        const val EXTRA_TASK = "task"

        // Which repeat this is, carried on the alarm intent, and how many times a reminder
        // asks again before it accepts that it is being ignored.
        const val EXTRA_NAG = "nag"
        const val MAX_NAGS = 8

        const val ACTION_FIRE = "io.github.hasanismail.themachine.REMINDER_FIRE"
        const val ACTION_DONE = "io.github.hasanismail.themachine.REMINDER_DONE"
        const val ACTION_SNOOZE = "io.github.hasanismail.themachine.REMINDER_SNOOZE"
        const val CHANNEL_ID = "reminders"
        private const val SALT_STRIDE = 31
        private const val SALT_DONE = 1
        private const val SALT_SNOOZE = 2
    }
}
