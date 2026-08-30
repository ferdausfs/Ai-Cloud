package dev.repochat.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.repochat.ui.chat.ChatScreen
import dev.repochat.ui.repopicker.RepoPickerScreen
import dev.repochat.ui.settings.SettingsScreen

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(
    navController: NavHostController,
    sharedTransitionScope: SharedTransitionScope,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = SettingsRoute,
        modifier = modifier,
        enterTransition = {
            fadeIn(tween(280)) + slideInHorizontally(tween(300)) { it / 6 }
        },
        exitTransition = {
            fadeOut(tween(200)) + slideOutHorizontally(tween(240)) { -it / 8 }
        },
        popEnterTransition = {
            fadeIn(tween(280)) + slideInHorizontally(tween(300)) { -it / 6 }
        },
        popExitTransition = {
            fadeOut(tween(200)) + slideOutHorizontally(tween(240)) { it / 8 }
        },
    ) {
        composable<SettingsRoute> {
            SettingsScreen(
                onBrowseRepos = {
                    navController.navigate(RepoPickerRoute) { launchSingleTop = true }
                },
                onOpenGeneralChat = {
                    navController.navigate(
                        ChatRoute(
                            owner = "",
                            repo = "",
                            defaultBranch = "",
                            mode = "GENERAL",
                            repoKey = "",
                        ),
                    ) {
                        launchSingleTop = true
                    }
                },
                onResumeRepo = { owner, repo, defaultBranch ->
                    navController.navigate(
                        ChatRoute(
                            owner = owner,
                            repo = repo,
                            defaultBranch = defaultBranch,
                            mode = "REPO",
                            repoKey = "$owner/$repo",
                        ),
                    ) {
                        // Reuse any existing chat entry for this repo instead
                        // of stacking duplicates.
                        popUpTo(SettingsRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<RepoPickerRoute> {
            RepoPickerScreen(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(SettingsRoute) {
                        popUpTo(SettingsRoute) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onRepoSelected = { repo ->
                    navController.navigate(
                        ChatRoute(
                            owner = repo.owner,
                            repo = repo.name,
                            defaultBranch = repo.defaultBranch,
                            mode = "REPO",
                            repoKey = repo.fullName,
                        ),
                    ) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            ChatScreen(
                owner = route.owner,
                repo = route.repo,
                defaultBranch = route.defaultBranch,
                mode = route.mode,
                repoKey = route.repoKey,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(SettingsRoute) { launchSingleTop = true }
                },
            )
        }
    }
}

