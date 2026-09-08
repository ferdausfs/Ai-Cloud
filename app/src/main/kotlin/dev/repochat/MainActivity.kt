package dev.repochat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.repochat.navigation.AppNavHost
import dev.repochat.navigation.ChatRoute
import dev.repochat.navigation.HomeRoute
import dev.repochat.ui.theme.RepoChatTheme
import dev.repochat.ui.theme.ThemeViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link from the AI-turn notification. Held as a Compose-observable
     * Activity field so [onNewIntent] can update it after composition starts.
     */
    private var pendingChatRoute by mutableStateOf<ChatRoute?>(null)

    private val themeViewModel: ThemeViewModel by viewModels<ThemeViewModel>()

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingChatRoute = chatRouteFrom(intent)

        setContent {
            // Read the Activity field inside composition so snapshot state works.
            val deepLink = pendingChatRoute
            // In-app override wins over the system setting (null = follow system).
            val darkOverride by themeViewModel.darkOverride.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            RepoChatTheme(darkTheme = darkOverride ?: systemDark) {
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

    private fun chatRouteFrom(intent: Intent?): ChatRoute? {
        if (intent?.action != ACTION_OPEN_CHAT) return null
        val repoKey = intent.getStringExtra(EXTRA_REPO_KEY).orEmpty()
        val owner = intent.getStringExtra(EXTRA_OWNER)?.takeIf { it.isNotBlank() }
        val repo = intent.getStringExtra(EXTRA_REPO)?.takeIf { it.isNotBlank() }
        if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
            // General/notification tap — open the conversation if known, else a fresh chat.
            return ChatRoute(owner = "", repo = "", defaultBranch = "", repoKey = repoKey)
        }
        val branch = intent.getStringExtra(EXTRA_DEFAULT_BRANCH)?.takeIf { it.isNotBlank() } ?: "main"
        return ChatRoute(
            owner = owner,
            repo = repo,
            defaultBranch = branch,
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
    }
}
