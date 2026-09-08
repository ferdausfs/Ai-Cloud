package dev.repochat.ui.theme

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the user's in-app dark/light override.
 *
 * `null` means "follow the system setting"; `true`/`false` are explicit
 * choices made from the top-bar toggle or the Settings switch. The choice is
 * persisted in plain SharedPreferences — a theme flag is not a secret.
 */
@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _darkOverride = MutableStateFlow(readPersisted())
    val darkOverride: StateFlow<Boolean?> = _darkOverride.asStateFlow()

    fun setDarkTheme(dark: Boolean?) {
        _darkOverride.value = dark
        prefs.edit().apply {
            if (dark == null) remove(KEY) else putInt(KEY, if (dark) 1 else 0)
        }.apply()
    }

    private fun readPersisted(): Boolean? = when (prefs.getInt(KEY, -1)) {
        1 -> true
        0 -> false
        else -> null
    }

    private companion object {
        const val PREFS = "ui_prefs"
        const val KEY = "dark_theme_override"
    }
}

/** Slim VM so composables (Settings, top bar) can read and flip the override. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val controller: ThemeController,
) : ViewModel() {
    val darkOverride: StateFlow<Boolean?> = controller.darkOverride

    fun setDarkTheme(dark: Boolean?) = controller.setDarkTheme(dark)
}
