package com.example.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Patterns
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONException

object RecipientManager {

    private const val TAG = "RecipientManager"
    private const val PREFS_FILE = "encrypted_recipients"
    private const val KEY_EMAILS = "email_recipients"
    private const val KEY_PHONES = "sms_recipients"

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

    private fun loadList(context: Context, key: String): MutableList<String> {
        val raw = getPrefs(context).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getString(it) }
        } catch (e: JSONException) {
            Log.e(TAG, "Corrupt recipient data for key=$key, resetting: ${e.message}")
            saveList(context, key, emptyList())
            mutableListOf()
        }
    }

    private fun saveList(context: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        getPrefs(context).edit().putString(key, arr.toString()).apply()
    }

    fun isValidEmail(email: String): Boolean {
        return email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    fun isValidPhone(phone: String): Boolean {
        val stripped = phone.replace(Regex("[\\s()-]"), "")
        return stripped.matches(Regex("^\\+?[0-9]{7,15}$"))
    }

    fun getEmails(context: Context): List<String> = synchronized(this) { loadList(context, KEY_EMAILS) }

    fun addEmail(context: Context, email: String): Boolean = synchronized(this) {
        val trimmed = email.trim().lowercase()
        if (!isValidEmail(trimmed)) return false
        val list = loadList(context, KEY_EMAILS)
        if (list.contains(trimmed)) return false
        list.add(trimmed)
        saveList(context, KEY_EMAILS, list)
        true
    }

    fun removeEmail(context: Context, email: String): Unit = synchronized(this) {
        val list = loadList(context, KEY_EMAILS)
        list.remove(email.trim().lowercase())
        saveList(context, KEY_EMAILS, list)
    }

    fun getPhones(context: Context): List<String> = synchronized(this) { loadList(context, KEY_PHONES) }

    fun addPhone(context: Context, phone: String): Boolean = synchronized(this) {
        val trimmed = phone.trim()
        if (!isValidPhone(trimmed)) return false
        val list = loadList(context, KEY_PHONES)
        if (list.contains(trimmed)) return false
        list.add(trimmed)
        saveList(context, KEY_PHONES, list)
        true
    }

    fun removePhone(context: Context, phone: String): Unit = synchronized(this) {
        val list = loadList(context, KEY_PHONES)
        list.remove(phone.trim())
        saveList(context, KEY_PHONES, list)
    }

    fun clearAll(context: Context) = synchronized(this) {
        saveList(context, KEY_EMAILS, emptyList())
        saveList(context, KEY_PHONES, emptyList())
    }
}
