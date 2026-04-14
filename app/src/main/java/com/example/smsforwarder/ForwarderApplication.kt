package com.example.smsforwarder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

class ForwarderApplication : Application() {

    companion object {
        const val CHANNEL_ID = "FORWARDER_CHANNEL"
        const val THEME_PREF_KEY = "THEME_MODE"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    override fun onCreate() {
        super.onCreate()
        applyThemeFromPrefs()
        createNotificationChannel()
    }

    private fun applyThemeFromPrefs() {
        val mode = getSharedPreferences("ForwarderPrefs", MODE_PRIVATE)
            .getInt(THEME_PREF_KEY, THEME_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TextSling",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when an intercepted SMS is being forwarded"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
