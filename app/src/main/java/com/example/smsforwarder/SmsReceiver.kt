package com.example.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.*

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences("ForwarderPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("IS_ENABLED", false)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress ?: "Unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        // Prevent infinite loop: skip if sender is one of our SMS recipients
        if (prefs.getBoolean("SMS_FORWARDING", true)) {
            val smsRecipients = RecipientManager.getPhones(context)
            if (smsRecipients.any { sender.endsWith(it.takeLast(10)) || it.endsWith(sender.takeLast(10)) }) {
                return
            }
        }

        val forwardAll = prefs.getBoolean("FORWARD_ALL_SMS", false)

        if (!forwardAll) {
            val otp = OtpDetector.extractOtp(body) ?: return
            scheduleForwarding(context, prefs, sender, body, timestamp, otp)
        } else {
            scheduleForwarding(context, prefs, sender, body, timestamp, null)
        }
    }

    private fun scheduleForwarding(
        context: Context,
        prefs: android.content.SharedPreferences,
        sender: String,
        body: String,
        timestamp: Long,
        extractedOtp: String?
    ) {
        val emailEnabled = prefs.getBoolean("EMAIL_FORWARDING", true)
        val smsEnabled = prefs.getBoolean("SMS_FORWARDING", true)
        val wm = WorkManager.getInstance(context)
        val uniqueSuffix = "${timestamp}_${sender}_${body.hashCode()}"

        val baseData = Data.Builder()
            .putString("sender", sender)
            .putString("body", body)
            .putLong("timestamp", timestamp)
        if (extractedOtp != null) {
            baseData.putString("extracted_otp", extractedOtp)
        }

        if (emailEnabled) {
            val emailData = Data.Builder()
                .putAll(baseData.putString("channel", "email").build())
                .build()

            val emailRequest = OneTimeWorkRequestBuilder<ForwardingWorker>()
                .setInputData(emailData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            wm.enqueueUniqueWork(
                "email_forward_$uniqueSuffix",
                ExistingWorkPolicy.KEEP,
                emailRequest
            )
        }

        if (smsEnabled) {
            val smsData = Data.Builder()
                .putAll(baseData.putString("channel", "sms").build())
                .build()

            val smsRequest = OneTimeWorkRequestBuilder<ForwardingWorker>()
                .setInputData(smsData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            wm.enqueueUniqueWork(
                "sms_forward_$uniqueSuffix",
                ExistingWorkPolicy.KEEP,
                smsRequest
            )
        }
    }
}
