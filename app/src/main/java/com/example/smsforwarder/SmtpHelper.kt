package com.example.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.net.ssl.SSLSocketFactory

/**
 * Sends email via Gmail SMTP with an App Password.
 * Uses raw socket SMTP over STARTTLS (port 587) -- no JavaMail dependency.
 * Credentials stored in EncryptedSharedPreferences.
 */
object SmtpHelper {

    private const val TAG = "SmtpHelper"
    private const val PREFS_FILE = "encrypted_smtp"
    private const val KEY_EMAIL = "smtp_email"
    private const val KEY_APP_PASSWORD = "smtp_app_password"

    private const val SMTP_HOST = "smtp.gmail.com"
    private const val SMTP_PORT = 587
    private const val TIMEOUT_MS = 15_000

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted prefs init failed, using transient fallback: ${e.message}")
                context.applicationContext.getSharedPreferences(PREFS_FILE + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    fun isConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        return !prefs.getString(KEY_EMAIL, "").isNullOrBlank()
                && !prefs.getString(KEY_APP_PASSWORD, "").isNullOrBlank()
    }

    fun getConfiguredEmail(context: Context): String? {
        val email = getPrefs(context).getString(KEY_EMAIL, "") ?: ""
        return email.ifBlank { null }
    }

    fun saveCredentials(context: Context, email: String, appPassword: String) {
        getPrefs(context).edit()
            .putString(KEY_EMAIL, email.trim().lowercase())
            .putString(KEY_APP_PASSWORD, appPassword.trim())
            .apply()
    }

    fun clearCredentials(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_EMAIL)
            .remove(KEY_APP_PASSWORD)
            .apply()
    }

    /**
     * Sends an email via SMTP with STARTTLS to smtp.gmail.com:587.
     * Raw socket implementation -- no javax.mail dependency.
     */
    suspend fun sendEmail(
        context: Context,
        to: String,
        subject: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        val prefs = getPrefs(context)
        val fromEmail = prefs.getString(KEY_EMAIL, "") ?: ""
        val appPassword = prefs.getString(KEY_APP_PASSWORD, "") ?: ""
        if (fromEmail.isBlank() || appPassword.isBlank()) {
            Log.e(TAG, "SMTP credentials not configured")
            return@withContext false
        }

        try {
            sendSmtp(fromEmail, appPassword, to, subject, body)
        } catch (e: Exception) {
            Log.e(TAG, "SMTP send failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun sendSmtp(
        from: String,
        password: String,
        to: String,
        subject: String,
        body: String
    ): Boolean {
        val encodedSubject = if (subject.all { it.code < 128 }) {
            subject
        } else {
            "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        }

        var socket = java.net.Socket()
        try {
            socket.connect(java.net.InetSocketAddress(SMTP_HOST, SMTP_PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS

            var reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var writer = OutputStreamWriter(socket.getOutputStream())

            fun read(): String = reader.readLine() ?: ""
            fun send(cmd: String) { writer.write(cmd + "\r\n"); writer.flush() }
            fun expect(prefix: String): String {
                val line = read()
                if (!line.startsWith(prefix)) throw Exception("Expected $prefix, got: $line")
                return line
            }

            expect("220")
            send("EHLO localhost")
            // Read all EHLO responses
            var line = read()
            while (line.length > 3 && line[3] == '-') { line = read() }

            send("STARTTLS")
            expect("220")

            // Upgrade to TLS
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, SMTP_HOST, SMTP_PORT, true) as javax.net.ssl.SSLSocket
            sslSocket.startHandshake()
            socket = sslSocket
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer = OutputStreamWriter(socket.getOutputStream())

            // Re-EHLO after TLS
            send("EHLO localhost")
            line = read()
            while (line.length > 3 && line[3] == '-') { line = read() }

            // AUTH LOGIN
            send("AUTH LOGIN")
            expect("334")
            send(Base64.encodeToString(from.toByteArray(), Base64.NO_WRAP))
            expect("334")
            send(Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
            val authResp = read()
            if (!authResp.startsWith("235")) {
                throw Exception("AUTH failed: $authResp")
            }

            send("MAIL FROM:<$from>")
            expect("250")
            send("RCPT TO:<$to>")
            expect("250")
            send("DATA")
            expect("354")

            // Message headers + body
            send("From: $from")
            send("To: $to")
            send("Subject: $encodedSubject")
            send("MIME-Version: 1.0")
            send("Content-Type: text/plain; charset=utf-8")
            send("Content-Transfer-Encoding: base64")
            send("")
            val b64Body = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
                .replace("\n", "\r\n")
            send(b64Body)
            send(".")
            expect("250")

            send("QUIT")
            try { read() } catch (_: Exception) {}

            return true
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
