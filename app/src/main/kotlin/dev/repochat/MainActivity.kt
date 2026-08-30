package dev.repochat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import dev.repochat.navigation.SettingsRoute
import dev.repochat.ui.theme.RepoChatTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link from the AI-turn notification. Held as a Compose-observable
     * Activity field so [onNewIntent] can update it after composition starts.
     */
    private var pendingChatRoute by mutableStateOf<ChatRoute?>(null)

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingChatRoute = chatRouteFrom(intent)

        setContent {
            // Read the Activity field inside composition so snapshot state works.
            val deepLink = pendingChatRoute
            RepoChatTheme {
                SharedTransitionLayout {
                    val navController = rememberNavController()
                    LaunchedEffect(deepLink) {
                        val route = deepLink ?: return@LaunchedEffect
                        navController.navigate(route) {
                            popUpTo(SettingsRoute) { inclusive = false }
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

    private fun chatRouteFrom(intent: Intent?): ChatRoute? {
        if (intent?.action != ACTION_OPEN_CHAT) return null
        val owner = intent.getStringExtra(EXTRA_OWNER)?.takeIf { it.isNotBlank() } ?: return null
        val repo = intent.getStringExtra(EXTRA_REPO)?.takeIf { it.isNotBlank() } ?: return null
        val branch = intent.getStringExtra(EXTRA_DEFAULT_BRANCH)?.takeIf { it.isNotBlank() } ?: "main"
        return ChatRoute(owner, repo, branch)
    }

    companion object {
        const val ACTION_OPEN_CHAT = "dev.repochat.OPEN_CHAT"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_DEFAULT_BRANCH = "default_branch"
    }
}
