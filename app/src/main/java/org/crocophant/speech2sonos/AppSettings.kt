package org.crocophant.speech2sonos

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    
    private val _amplification = MutableStateFlow(prefs.getInt(KEY_AMPLIFICATION, 2))
    val amplification: StateFlow<Int> = _amplification.asStateFlow()
    
    private val _announcementMode = MutableStateFlow(prefs.getBoolean(KEY_ANNOUNCEMENT_MODE, true))
    val announcementMode: StateFlow<Boolean> = _announcementMode.asStateFlow()
    
    private val _announcementVolume = MutableStateFlow(prefs.getInt(KEY_ANNOUNCEMENT_VOLUME, 15))
    val announcementVolume: StateFlow<Int> = _announcementVolume.asStateFlow()
    
    private val _pushToTalkMode = MutableStateFlow(prefs.getBoolean(KEY_PUSH_TO_TALK, false))
    val pushToTalkMode: StateFlow<Boolean> = _pushToTalkMode.asStateFlow()
    
    fun setAmplification(value: Int) {
        val clamped = value.coerceIn(1, 20)
        _amplification.value = clamped
        prefs.edit().putInt(KEY_AMPLIFICATION, clamped).apply()
    }
    
    fun setAnnouncementMode(enabled: Boolean) {
        _announcementMode.value = enabled
        prefs.edit().putBoolean(KEY_ANNOUNCEMENT_MODE, enabled).apply()
    }
    
    fun setAnnouncementVolume(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _announcementVolume.value = clamped
        prefs.edit().putInt(KEY_ANNOUNCEMENT_VOLUME, clamped).apply()
    }
    
    fun setPushToTalkMode(enabled: Boolean) {
        _pushToTalkMode.value = enabled
        prefs.edit().putBoolean(KEY_PUSH_TO_TALK, enabled).apply()
    }
    
    fun saveSelectedDeviceIPs(ipAddresses: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_DEVICES, ipAddresses).apply()
    }
    
    fun getSelectedDeviceIPs(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_DEVICES, emptySet()) ?: emptySet()
    }
    
    companion object {
        private const val KEY_AMPLIFICATION = "amplification"
        private const val KEY_ANNOUNCEMENT_MODE = "announcement_mode"
        private const val KEY_ANNOUNCEMENT_VOLUME = "announcement_volume"
        private const val KEY_PUSH_TO_TALK = "push_to_talk_mode"
        private const val KEY_SELECTED_DEVICES = "selected_device_ips"
    }
}
