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
import dev.repochat.navigation.RepoPickerRoute
import dev.repochat.navigation.SettingsRoute
import dev.repochat.ui.onboarding.FirstRunController
import dev.repochat.ui.onboarding.OnboardingScreen
import dev.repochat.ui.theme.RepoChatTheme
import dev.repochat.ui.theme.ThemeViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link / shortcut destination. Held as a Compose-observable Activity
     * field so [onNewIntent] can update it after composition starts. Carries
     * any type-safe nav route (chat, settings, repo picker).
     */
    private var pendingRoute by mutableStateOf<Any?>(null)

    @Inject
    lateinit var firstRun: FirstRunController

    private val themeViewModel: ThemeViewModel by viewModels<ThemeViewModel>()

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute = routeFrom(intent)

        setContent {
            // Read the Activity field inside composition so snapshot state works.
            val deepLink = pendingRoute
            // In-app override wins over the system setting (null = follow system).
            val darkOverride by themeViewModel.darkOverride.collectAsStateWithLifecycle()
            val amoled by themeViewModel.amoled.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            RepoChatTheme(
                darkTheme = darkOverride ?: systemDark,
                amoled = amoled,
            ) {
                val onboardingDone by firstRun.completed.collectAsStateWithLifecycle()
                if (!onboardingDone) {
                    OnboardingScreen(onFinished = { /* state flip swaps UI */ })
                } else {
                    SharedTransitionLayout {
                    val navController = rememberNavController()
                    LaunchedEffect(deepLink) {
                        val route = deepLink ?: return@LaunchedEffect
                        navController.navigate(route) {
                            popUpTo(HomeRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                        pendingRoute = null
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = routeFrom(intent)
    }

    private fun routeFrom(intent: Intent?): Any? = when (intent?.action) {
        ACTION_OPEN_CHAT -> chatRouteFrom(intent)
        ACTION_OPEN_SETTINGS -> SettingsRoute
        ACTION_OPEN_REPOS -> RepoPickerRoute
        else -> null
    }

    private fun chatRouteFrom(intent: Intent?): ChatRoute? {
        val repoKey = intent?.getStringExtra(EXTRA_REPO_KEY).orEmpty()
        val owner = intent?.getStringExtra(EXTRA_OWNER)?.takeIf { it.isNotBlank() }
        val repo = intent?.getStringExtra(EXTRA_REPO)?.takeIf { it.isNotBlank() }
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
        const val ACTION_OPEN_SETTINGS = "dev.repochat.OPEN_SETTINGS"
        const val ACTION_OPEN_REPOS = "dev.repochat.OPEN_REPOS"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_DEFAULT_BRANCH = "default_branch"
        const val EXTRA_MODE = "mode"
        const val EXTRA_REPO_KEY = "repo_key"
    }
}
