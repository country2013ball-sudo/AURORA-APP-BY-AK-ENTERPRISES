package com.akenterprises.auroraengine

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AuroraApplication : Application() {

    companion object {
        const val CHANNEL_FRAMEGEN_ID = "aurora_framegen_channel"
        const val CHANNEL_OVERLAY_ID = "aurora_overlay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return

            val frameGenChannel = NotificationChannel(
                CHANNEL_FRAMEGEN_ID,
                getString(R.string.notif_channel_framegen),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required foreground notification for user-approved Aurora Frame Generation capture analysis."
                setShowBadge(false)
            }

            val overlayChannel = NotificationChannel(
                CHANNEL_OVERLAY_ID,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while a user-enabled Aurora floating control overlay is visible."
                setShowBadge(false)
            }

            nm.createNotificationChannel(frameGenChannel)
            nm.createNotificationChannel(overlayChannel)
        }
    }
}
