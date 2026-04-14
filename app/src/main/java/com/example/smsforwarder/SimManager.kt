package com.example.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class SimInfo(
    val subscriptionId: Int,
    val displayName: String,
    val slotIndex: Int,
    val number: String?
) {
    val label: String
        get() {
            val numPart = if (!number.isNullOrBlank()) " ($number)" else ""
            return "SIM ${slotIndex + 1} - $displayName$numPart"
        }
}

object SimManager {

    private const val PREF_KEY = "PREFERRED_SIM_SUB_ID"
    private const val DEFAULT_SUB_ID = -1

    fun getAvailableSims(context: Context): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val subManager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val subs: List<SubscriptionInfo> = try {
            subManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

        return subs.map { info ->
            SimInfo(
                subscriptionId = info.subscriptionId,
                displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                slotIndex = info.simSlotIndex,
                number = info.number
            )
        }
    }

    fun getPreferredSubId(context: Context): Int {
        return context.getSharedPreferences("ForwarderPrefs", Context.MODE_PRIVATE)
            .getInt(PREF_KEY, DEFAULT_SUB_ID)
    }

    fun setPreferredSubId(context: Context, subId: Int) {
        context.getSharedPreferences("ForwarderPrefs", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, subId).apply()
    }

    fun getSmsManager(context: Context): SmsManager {
        val subId = getPreferredSubId(context)
        return if (subId != DEFAULT_SUB_ID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }
    }
}
