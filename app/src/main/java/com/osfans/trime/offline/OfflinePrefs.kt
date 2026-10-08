package com.osfans.trime.offline

import androidx.preference.PreferenceManager
import com.osfans.trime.util.appContext

object OfflinePrefs {
    val shared get() = PreferenceManager.getDefaultSharedPreferences(appContext)
    fun number(key: String, default: Int) = shared.getInt(key, default)
    val height get() = number("offline_height", 100).coerceIn(70, 160)
    val width get() = number("offline_width", 100).coerceIn(65, 100)
    val position get() = number("offline_position", 50).coerceIn(0, 100)
    val bottom get() = number("offline_bottom", 0).coerceIn(0, 100)
    val textScale get() = number("offline_text", 100).coerceIn(70, 150) / 100f
    val candidateHeight get() = number("offline_candidate_height", 56).coerceIn(40, 96)
    val pinyinHeight get() = number("offline_pinyin_height", 64).coerceIn(48, 120)
    val pinyinText get() = number("offline_pinyin_text", 26).coerceIn(18, 40)
    val layoutSignature get() = "$height/$width/$position/$bottom/$textScale/$candidateHeight/$pinyinHeight/$pinyinText"
    val systemHaptics get() = shared.getString("offline_haptic_mode", "system") != "custom"
    val learning get() = shared.getBoolean("offline_learning", true)
}

