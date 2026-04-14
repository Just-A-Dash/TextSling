package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the app after device reboot.
 * WorkManager persists its queue in SQLite and automatically re-schedules
 * pending jobs on boot. This receiver exists so the OS instantiates our
 * process, giving WorkManager the chance to initialize.
 * No explicit action is needed beyond being alive.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            androidx.work.WorkManager.getInstance(context)
        }
    }
}
