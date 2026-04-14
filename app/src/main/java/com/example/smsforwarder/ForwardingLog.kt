package com.example.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class LogEntry(
    val timestamp: Long,
    val sender: String,
    val extractedOtp: String?,
    val channel: String,
    val success: Boolean,
    val errorMsg: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", timestamp)
        put("sender", sender)
        put("otp", extractedOtp ?: "")
        put("ch", channel)
        put("ok", success)
        put("err", errorMsg ?: "")
    }

    companion object {
        fun fromJson(obj: JSONObject): LogEntry = LogEntry(
            timestamp = obj.optLong("ts", 0),
            sender = obj.optString("sender", "?"),
            extractedOtp = obj.optString("otp", "").ifEmpty { null },
            channel = obj.optString("ch", "?"),
            success = obj.optBoolean("ok", false),
            errorMsg = obj.optString("err", "").ifEmpty { null }
        )
    }
}

object ForwardingLog {

    private const val TAG = "ForwardingLog"
    private const val PREFS_FILE = "encrypted_log"
    private const val KEY_LOG = "forwarding_log"
    private const val MAX_ENTRIES = 50

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

    fun addEntry(context: Context, entry: LogEntry) {
        synchronized(this) {
            val list = loadEntries(context).toMutableList()
            list.add(0, entry)
            while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            getPrefs(context).edit().putString(KEY_LOG, arr.toString()).apply()
        }
    }

    fun getEntries(context: Context): List<LogEntry> = synchronized(this) { loadEntries(context) }

    fun clear(context: Context) {
        synchronized(this) {
            getPrefs(context).edit().putString(KEY_LOG, "[]").apply()
        }
    }

    private fun loadEntries(context: Context): List<LogEntry> {
        val raw = getPrefs(context).getString(KEY_LOG, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { LogEntry.fromJson(arr.getJSONObject(it)) }
        } catch (e: JSONException) {
            Log.e(TAG, "Corrupt log data, resetting: ${e.message}")
            getPrefs(context).edit().putString(KEY_LOG, "[]").apply()
            emptyList()
        }
    }
}
