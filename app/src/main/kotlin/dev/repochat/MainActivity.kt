package dev.repochat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.repochat.navigation.AppNavHost
import dev.repochat.ui.theme.RepoChatTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RepoChatTheme {
                // SharedTransitionLayout powers the shared-element transition
                // between the repo picker and the chat screen.
                SharedTransitionLayout {
                    val navController = rememberNavController()
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
