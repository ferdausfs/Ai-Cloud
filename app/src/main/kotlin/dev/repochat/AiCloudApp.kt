package dev.repochat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import dev.repochat.turn.AiTurnService

@HiltAndroidApp
class AiCloudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First thing we do — even startup crashes get captured for the
        // next launch's share dialog (see MainActivity).
        CrashTrap.install(this)
        ensureTurnNotificationChannel()
    }

    private fun ensureTurnNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(AiTurnService.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                AiTurnService.CHANNEL_ID,
                getString(R.string.turn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.turn_notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }
}
