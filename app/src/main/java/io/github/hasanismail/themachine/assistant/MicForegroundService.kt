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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * A do-nothing foreground service whose only job is to exist while the assistant records.
 *
 * The microphone permission is granted "while in use", and the assistant records from a
 * background overlay, so Android's foreground-only appop silently feeds it silence — proven
 * on device: the same capture reads real audio with the app foregrounded and zeros with it
 * backgrounded. A foreground service of type microphone puts the app in the foreground proc
 * state the appop checks for, so the microphone actually opens. It holds no state and does
 * no work; it is a proc-state token, nothing more.
 */
class MicForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Assistant is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TheMachine"
        private const val CHANNEL = "assistant_mic"
        private const val NOTIF_ID = 42

        /** Elevates the app to a foreground state for the microphone. Safe to call repeatedly. */
        fun start(context: Context) {
            ensureChannel(context)
            runCatching {
                context.startForegroundService(Intent(context, MicForegroundService::class.java))
                Log.i(TAG, "mic foreground service started")
            }.onFailure { Log.w(TAG, "mic foreground service could not start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MicForegroundService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Assistant microphone", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
