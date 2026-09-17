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
 * Holds the user's in-app dark/light override and the AMOLED (true-black)
 * preference.
 *
 * `darkOverride == null` means "follow the system setting"; `true`/`false`
 * are explicit choices made from the top-bar toggle or the Settings switch.
 * AMOLED only affects dark mode (pure-black surfaces). Both flags persist in
 * plain SharedPreferences — theme flags are not secrets.
 */
@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _darkOverride = MutableStateFlow(readPersisted())
    val darkOverride: StateFlow<Boolean?> = _darkOverride.asStateFlow()

    private val _amoled = MutableStateFlow(prefs.getBoolean(KEY_AMOLED, false))
    val amoled: StateFlow<Boolean> = _amoled.asStateFlow()

    fun setDarkTheme(dark: Boolean?) {
        _darkOverride.value = dark
        prefs.edit().apply {
            if (dark == null) remove(KEY) else putInt(KEY, if (dark) 1 else 0)
        }.apply()
    }

    fun setAmoled(enabled: Boolean) {
        _amoled.value = enabled
        prefs.edit().putBoolean(KEY_AMOLED, enabled).apply()
    }

    private fun readPersisted(): Boolean? = when (prefs.getInt(KEY, -1)) {
        1 -> true
        0 -> false
        else -> null
    }

    private companion object {
        const val PREFS = "ui_prefs"
        const val KEY = "dark_theme_override"
        const val KEY_AMOLED = "amoled_theme"
    }
}

/** Slim VM so composables (Settings, top bar) can read and flip the overrides. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val controller: ThemeController,
) : ViewModel() {
    val darkOverride: StateFlow<Boolean?> = controller.darkOverride
    val amoled: StateFlow<Boolean> = controller.amoled

    fun setDarkTheme(dark: Boolean?) = controller.setDarkTheme(dark)

    fun setAmoled(enabled: Boolean) = controller.setAmoled(enabled)
}
