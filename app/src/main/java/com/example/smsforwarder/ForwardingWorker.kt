package com.example.smsforwarder

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ForwardingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ForwardingWorker"
    }

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground: ${e.message}")
        }

        val sender = inputData.getString("sender") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val timestamp = inputData.getLong("timestamp", System.currentTimeMillis())
        val extractedOtp = inputData.getString("extracted_otp")
        val channel = inputData.getString("channel") ?: return Result.failure()

        val sentTracker = applicationContext.getSharedPreferences("sent_tracker", Context.MODE_PRIVATE)
        val trackKey = "${id}_${channel}"
        val alreadySent = sentTracker.getStringSet(trackKey, emptySet()) ?: emptySet()

        val (success, newlySent) = when (channel) {
            "email" -> forwardViaEmail(sender, body, timestamp, extractedOtp, alreadySent)
            "sms" -> forwardViaSms(sender, body, extractedOtp, alreadySent)
            else -> {
                Log.e(TAG, "Unknown channel: $channel")
                false to emptySet<String>()
            }
        }

        if (newlySent.isNotEmpty()) {
            sentTracker.edit().putStringSet(trackKey, alreadySent + newlySent).apply()
        }

        return if (!success && runAttemptCount < 5) {
            Result.retry()
        } else {
            sentTracker.edit().remove(trackKey).apply()
            ForwardingLog.addEntry(applicationContext, LogEntry(
                timestamp = System.currentTimeMillis(),
                sender = sender,
                extractedOtp = extractedOtp,
                channel = channel,
                success = success,
                errorMsg = if (!success) "Failed after ${runAttemptCount + 1} attempts" else null
            ))
            if (!success) {
                Log.e(TAG, "Forwarding failed after ${runAttemptCount + 1} attempts: channel=$channel")
                Result.failure()
            } else {
                Result.success()
            }
        }
    }

    private suspend fun forwardViaEmail(
        sender: String,
        body: String,
        timestamp: Long,
        extractedOtp: String?,
        alreadySent: Set<String>
    ): Pair<Boolean, Set<String>> {
        val recipients = RecipientManager.getEmails(applicationContext)
        if (recipients.isEmpty()) return true to emptySet()

        val remaining = recipients.filter { it !in alreadySent }
        if (remaining.isEmpty()) return true to emptySet()

        val prefs = applicationContext.getSharedPreferences("ForwarderPrefs", Context.MODE_PRIVATE)
        val useSmtp = prefs.getString("EMAIL_METHOD", "smtp") == "smtp"

        if (useSmtp) {
            if (!SmtpHelper.isConfigured(applicationContext)) {
                Log.w(TAG, "SMTP credentials not configured, skipping email")
                return false to emptySet()
            }
        } else {
            if (!GmailHelper.isSignedIn(applicationContext)) {
                Log.w(TAG, "Gmail OAuth not signed in, skipping email")
                return false to emptySet()
            }
        }

        val subject = if (extractedOtp != null) {
            "OTP: $extractedOtp (from $sender)"
        } else {
            "SMS from $sender"
        }

        val timeStr = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date(timestamp))

        val emailBody = buildString {
            if (extractedOtp != null) {
                append("Extracted OTP: $extractedOtp\n\n")
            }
            append("From: $sender\n")
            append("Time: $timeStr\n")
            append("\nFull message:\n$body")
        }

        val succeeded = mutableSetOf<String>()
        var anyFailed = false
        for (recipient in remaining) {
            val ok = if (useSmtp) {
                SmtpHelper.sendEmail(applicationContext, recipient, subject, emailBody)
            } else {
                GmailHelper.sendEmail(applicationContext, recipient, subject, emailBody)
            }
            if (ok) {
                succeeded.add(recipient)
            } else {
                Log.e(TAG, "Failed to email $recipient")
                anyFailed = true
            }
        }
        return !anyFailed to succeeded
    }

    private suspend fun forwardViaSms(
        sender: String,
        body: String,
        extractedOtp: String?,
        alreadySent: Set<String>
    ): Pair<Boolean, Set<String>> = withContext(Dispatchers.IO) {
        val recipients = RecipientManager.getPhones(applicationContext)
        if (recipients.isEmpty()) return@withContext (true to emptySet())

        val remaining = recipients.filter { it !in alreadySent }
        if (remaining.isEmpty()) return@withContext (true to emptySet())

        val smsText = if (extractedOtp != null) {
            "OTP $extractedOtp from $sender"
        } else {
            "Fwd from $sender: $body"
        }
        val smsManager = SimManager.getSmsManager(applicationContext)

        val succeeded = mutableSetOf<String>()
        var anyFailed = false
        for (phone in remaining) {
            try {
                val parts = smsManager.divideMessage(smsText)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(phone, null, smsText, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                }
                succeeded.add(phone)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to SMS $phone: ${e.message}")
                anyFailed = true
            }
        }
        !anyFailed to succeeded
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = inputData.getString("channel") ?: "forward"
        val notifId = (channel.hashCode() and 0x7FFFFFFF) or 1

        val notification = NotificationCompat.Builder(
            applicationContext,
            ForwarderApplication.CHANNEL_ID
        )
            .setContentTitle("Forwarding SMS")
            .setContentText("Transmitting via $channel...")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notifId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            ForegroundInfo(notifId, notification)
        }
    }
}
