package org.crocophant.speech2sonos

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    
    private val _amplification = MutableStateFlow(prefs.getInt(KEY_AMPLIFICATION, 10))
    val amplification: StateFlow<Int> = _amplification.asStateFlow()
    
    private val _showTestButton = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TEST_BUTTON, false))
    val showTestButton: StateFlow<Boolean> = _showTestButton.asStateFlow()
    
    fun setAmplification(value: Int) {
        val clamped = value.coerceIn(1, 20)
        _amplification.value = clamped
        prefs.edit().putInt(KEY_AMPLIFICATION, clamped).apply()
    }
    
    fun setShowTestButton(show: Boolean) {
        _showTestButton.value = show
        prefs.edit().putBoolean(KEY_SHOW_TEST_BUTTON, show).apply()
    }
    
    companion object {
        private const val KEY_AMPLIFICATION = "amplification"
        private const val KEY_SHOW_TEST_BUTTON = "show_test_button"
    }
}
