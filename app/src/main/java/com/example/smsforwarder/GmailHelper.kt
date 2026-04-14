package com.example.smsforwarder

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GmailHelper {

    private const val TAG = "GmailHelper"

    // Replace with your OAuth2 client ID from Google Cloud Console
    const val GOOGLE_CLIENT_ID = "YOUR_CLIENT_ID.apps.googleusercontent.com"

    private const val GMAIL_SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
    private const val SCOPE_GMAIL_SEND = "oauth2:https://www.googleapis.com/auth/gmail.send"

    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(GOOGLE_CLIENT_ID)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun isSignedIn(context: Context): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    fun getSignedInEmail(context: Context): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    fun signOut(context: Context, onComplete: () -> Unit) {
        getSignInClient(context).signOut().addOnCompleteListener { onComplete() }
    }

    /**
     * Fetches a fresh OAuth2 access token from Google Play Services.
     * Play Services manages the token cache and refresh -- we never store it.
     */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account
            ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(context, account, SCOPE_GMAIL_SEND)
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "User consent required for Gmail send scope. User must re-sign-in.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get access token: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Sends an email via the Gmail REST API.
     * Constructs an RFC 2822 message with proper encoding for non-ASCII content,
     * base64url-encodes it, and POSTs to the Gmail send endpoint.
     */
    suspend fun sendEmail(
        context: Context,
        to: String,
        subject: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        val token = getAccessToken(context) ?: return@withContext false
        val fromEmail = getSignedInEmail(context) ?: return@withContext false

        var conn: HttpURLConnection? = null
        try {
            val raw = buildRawMime(fromEmail, to, subject, body)
            val json = JSONObject().apply {
                put("raw", raw)
            }

            conn = (URL(GMAIL_SEND_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
            val code = conn.responseCode

            if (code !in 200..299) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
                } catch (_: Exception) { "unreadable" }
                Log.e(TAG, "Gmail API returned $code: $errorBody")
            }

            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "sendEmail failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Builds an RFC 2822 compliant MIME message with proper encoding:
     * - Subject encoded with RFC 2047 =?UTF-8?B?...?= for non-ASCII
     * - Body encoded as base64 Content-Transfer-Encoding for full UTF-8 support
     */
    private fun buildRawMime(
        from: String,
        to: String,
        subject: String,
        body: String
    ): String {
        val encodedSubject = if (subject.all { it.code < 128 }) {
            subject
        } else {
            val b64 = Base64.encodeToString(
                subject.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            "=?UTF-8?B?$b64?="
        }

        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val encodedBody = Base64.encodeToString(bodyBytes, Base64.DEFAULT)

        val mime = buildString {
            append("From: $from\r\n")
            append("To: $to\r\n")
            append("Subject: $encodedSubject\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            append(encodedBody)
        }
        return Base64.encodeToString(
            mime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
