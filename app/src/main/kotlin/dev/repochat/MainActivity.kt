package dev.repochat

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.repochat.navigation.AppNavHost
import dev.repochat.navigation.ChatRoute
import dev.repochat.navigation.HomeRoute
import dev.repochat.ui.theme.RepoChatTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link from the AI-turn notification. Held as a Compose-observable
     * Activity field so [onNewIntent] can update it after composition starts.
     */
    private var pendingChatRoute by mutableStateOf<ChatRoute?>(null)

    /** One-shot runtime notification permission prompt (audit BUG-203). */
    private var requestNotifications by mutableStateOf(false)

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingChatRoute = chatRouteFrom(intent)
        requestNotifications = shouldRequestNotificationPermission()

        setContent {
            // Read the Activity field inside composition so snapshot state works.
            val deepLink = pendingChatRoute
            val notifPermission = requestNotifications
            val notifLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* deniable — the app works without visible notifications */ }
            LaunchedEffect(notifPermission) {
                if (notifPermission) {
                    markNotificationPermissionAsked()
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    requestNotifications = false
                }
            }
            RepoChatTheme {
                SharedTransitionLayout {
                    val navController = rememberNavController()
                    LaunchedEffect(deepLink) {
                        val route = deepLink ?: return@LaunchedEffect
                        navController.navigate(route) {
                            popUpTo(HomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                        pendingChatRoute = null
                    }
                    AppNavHost(
                        navController = navController,
                        sharedTransitionScope = this,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingChatRoute = chatRouteFrom(intent)
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        // Android 13+ requires a runtime grant for the AI-turn foreground
        // service notification to be visible. Never re-ask after a denial —
        // the OS then only allows granting via system settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val previouslyAsked = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_NOTIF_ASKED, false)
        return !granted && !previouslyAsked
    }

    private fun markNotificationPermissionAsked() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIF_ASKED, true)
            .apply()
    }

    private fun chatRouteFrom(intent: Intent?): ChatRoute? {
        if (intent?.action != ACTION_OPEN_CHAT) return null
        val mode = intent.getStringExtra(EXTRA_MODE)?.takeIf { it.isNotBlank() } ?: "REPO"
        val repoKey = intent.getStringExtra(EXTRA_REPO_KEY).orEmpty()
        if (mode.equals("GENERAL", ignoreCase = true)) {
            return ChatRoute(
                owner = "",
                repo = "",
                defaultBranch = "",
                mode = "GENERAL",
                repoKey = repoKey,
            )
        }
        val owner = intent.getStringExtra(EXTRA_OWNER)?.takeIf { it.isNotBlank() } ?: return null
        val repo = intent.getStringExtra(EXTRA_REPO)?.takeIf { it.isNotBlank() } ?: return null
        val branch = intent.getStringExtra(EXTRA_DEFAULT_BRANCH)?.takeIf { it.isNotBlank() } ?: "main"
        return ChatRoute(
            owner = owner,
            repo = repo,
            defaultBranch = branch,
            mode = "REPO",
            repoKey = repoKey.ifBlank { "$owner/$repo" },
        )
    }

    companion object {
        const val ACTION_OPEN_CHAT = "dev.repochat.OPEN_CHAT"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_DEFAULT_BRANCH = "default_branch"
        const val EXTRA_MODE = "mode"
        const val EXTRA_REPO_KEY = "repo_key"
        private const val PREFS = "main_activity_prefs"
        private const val KEY_NOTIF_ASKED = "notifications_permission_asked"
    }
}
