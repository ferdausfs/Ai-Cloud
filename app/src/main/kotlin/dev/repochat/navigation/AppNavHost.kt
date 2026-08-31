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
import dev.repochat.ui.home.HomeScaffold
import dev.repochat.ui.repopicker.RepoPickerScreen
import dev.repochat.ui.repos.RepoDetailScreen
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
        startDestination = HomeRoute,
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
        composable<HomeRoute> {
            HomeScaffold(
                onOpenChat = { owner, repo, defaultBranch, mode, repoKey ->
                    navController.navigate(
                        ChatRoute(owner, repo, defaultBranch, mode, repoKey),
                    ) { launchSingleTop = true }
                },
                onOpenGeneralChat = {
                    navController.navigate(
                        ChatRoute("", "", "", "GENERAL", ""),
                    ) { launchSingleTop = true }
                },
                onOpenRepoDetail = { owner, repo, defaultBranch ->
                    navController.navigate(RepoDetailRoute(owner, repo, defaultBranch)) {
                        launchSingleTop = true
                    }
                },
                onOpenRepos = {
                    navController.navigate(RepoPickerRoute) { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(SettingsRoute) { launchSingleTop = true }
                },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<RepoPickerRoute> {
            RepoPickerScreen(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this,
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(SettingsRoute) { launchSingleTop = true }
                },
                onRepoSelected = { repo ->
                    navController.navigate(
                        RepoDetailRoute(repo.owner, repo.name, repo.defaultBranch),
                    ) { launchSingleTop = true }
                },
            )
        }

        composable<RepoDetailRoute> { entry ->
            val route = entry.toRoute<RepoDetailRoute>()
            RepoDetailScreen(
                owner = route.owner,
                repo = route.repo,
                defaultBranch = route.defaultBranch,
                onBack = { navController.popBackStack() },
                onChatAboutRepo = {
                    navController.navigate(
                        ChatRoute(
                            owner = route.owner,
                            repo = route.repo,
                            defaultBranch = route.defaultBranch,
                            mode = "REPO",
                            repoKey = "${route.owner}/${route.repo}",
                        ),
                    ) { launchSingleTop = true }
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
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(HomeRoute) { launchSingleTop = true }
                    }
                },
                onOpenSettings = {
                    navController.navigate(SettingsRoute) { launchSingleTop = true }
                },
            )
        }
    }
}
