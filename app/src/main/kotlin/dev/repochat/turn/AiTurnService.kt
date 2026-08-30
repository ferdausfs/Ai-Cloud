package dev.repochat.turn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.repochat.MainActivity
import dev.repochat.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and network) alive for the
 * duration of an in-flight AI turn. The turn coroutine itself lives in
 * [AiTurnCoordinator] — this service only holds the FGS notification.
 *
 * Type: dataSync (network sync work on modern Android).
 */
@AndroidEntryPoint
class AiTurnService : Service() {

    @Inject lateinit var coordinator: AiTurnCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    private var owner: String = ""
    private var repo: String = ""
    private var defaultBranch: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        owner = intent?.getStringExtra(EXTRA_OWNER).orEmpty()
        repo = intent?.getStringExtra(EXTRA_REPO).orEmpty()
        defaultBranch = intent?.getStringExtra(EXTRA_DEFAULT_BRANCH).orEmpty()

        ensureChannel()
        val notification = buildNotification(
            title = getString(R.string.turn_notification_title, repo.ifBlank { "repo" }),
            text = getString(R.string.turn_step_starting),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        observeJob?.cancel()
        observeJob = serviceScope.launch {
            coordinator.state.collectLatest { state ->
                val step = state.workingStep.ifBlank {
                    when {
                        state.approvalPending -> getString(R.string.turn_step_awaiting_approval)
                        state.typing -> getString(R.string.turn_step_thinking)
                        else -> getString(R.string.turn_step_starting)
                    }
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = getString(
                            R.string.turn_notification_title,
                            state.repo.ifBlank { repo }.ifBlank { "repo" },
                        ),
                        text = step,
                    ),
                )
                // Stop when the turn is fully idle (not typing, not awaiting approval).
                if (!state.active && !state.typing && !state.approvalPending && !state.approving) {
                    stopSelfSafely()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Already stopped.
        }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
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

    private fun buildNotification(title: String, text: String): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CHAT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OWNER, owner)
            putExtra(MainActivity.EXTRA_REPO, repo)
            putExtra(MainActivity.EXTRA_DEFAULT_BRANCH, defaultBranch)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "ai_agent_activity"
        const val NOTIFICATION_ID = 42_001
        private const val EXTRA_OWNER = "owner"
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_DEFAULT_BRANCH = "default_branch"

        fun start(context: Context, owner: String, repo: String, defaultBranch: String) {
            val intent = Intent(context, AiTurnService::class.java).apply {
                putExtra(EXTRA_OWNER, owner)
                putExtra(EXTRA_REPO, repo)
                putExtra(EXTRA_DEFAULT_BRANCH, defaultBranch)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // stopService → onDestroy; avoid re-entering startForeground.
            context.stopService(Intent(context, AiTurnService::class.java))
        }
    }
}
