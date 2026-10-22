package com.example.bthviewing

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки приложения: тема и блокировка поворота экрана.
 * Хранятся в SharedPreferences, переживают перезапуск.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS = "app_prefs"
        private const val KEY_DARK = "dark_theme"
        private const val KEY_LOCK_PORTRAIT = "lock_portrait"
    }

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK, false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private val _lockPortrait = MutableStateFlow(prefs.getBoolean(KEY_LOCK_PORTRAIT, false))
    val lockPortrait: StateFlow<Boolean> = _lockPortrait.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        _darkTheme.value = enabled
        prefs.edit().putBoolean(KEY_DARK, enabled).apply()
    }

    fun setLockPortrait(enabled: Boolean) {
        _lockPortrait.value = enabled
        prefs.edit().putBoolean(KEY_LOCK_PORTRAIT, enabled).apply()
    }

    /** Прочитать сохранённое значение при старте Activity — до setContent. */
    fun readLockPortrait(): Boolean = prefs.getBoolean(KEY_LOCK_PORTRAIT, false)
}