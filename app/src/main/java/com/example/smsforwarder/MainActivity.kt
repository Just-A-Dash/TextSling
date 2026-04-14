package com.example.smsforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.smsforwarder.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private var simOptions = listOf<Pair<Int, String>>()
    private var suppressListeners = false

    // ── Activity Result Launchers ──

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
        } catch (e: Exception) {
            snack("Sign-in failed: ${e.message}")
        }
        updateEmailStatus()
        refreshReadiness()
    }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            snack("SMS permissions granted")
        } else {
            snack("Some SMS permissions were denied")
            prefs.edit().putBoolean("IS_ENABLED", false).apply()
            binding.switchMaster.isChecked = false
        }
        refreshReadiness()
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        snack(if (granted) "Notification permission granted" else "Notification permission denied")
        refreshReadiness()
    }

    private val sendSmsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runTestSms() else snack("SEND_SMS permission denied")
    }

    private val phoneStatePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) refreshSimDropdown() else snack("Phone state permission denied")
    }

    // ── Lifecycle ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("ForwarderPrefs", Context.MODE_PRIVATE)

        setSupportActionBar(binding.toolbar)
        setupCollapsibleSections()

        // ── Master Toggle ──
        binding.switchMaster.isChecked = prefs.getBoolean("IS_ENABLED", false)
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("IS_ENABLED", isChecked).apply()
            if (isChecked) requestSmsPermissions()
            refreshReadiness()
        }

        // ── Email Method ──
        setupEmailMethodSelector()
        updateEmailStatus()

        binding.btnConfigureSmtp.setOnClickListener { showSmtpConfigDialog() }
        binding.btnGmailSignIn.setOnClickListener {
            signInLauncher.launch(GmailHelper.getSignInClient(this).signInIntent)
        }
        binding.btnGmailSignOut.setOnClickListener {
            GmailHelper.signOut(this) {
                updateEmailStatus()
                refreshReadiness()
            }
        }

        // ── Forwarding Mode ──
        binding.switchForwardAll.isChecked = prefs.getBoolean("FORWARD_ALL_SMS", false)
        binding.switchForwardAll.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("FORWARD_ALL_SMS", isChecked).apply()
        }

        // ── Forwarding Toggles ──
        binding.switchEmailForward.isChecked = prefs.getBoolean("EMAIL_FORWARDING", true)
        binding.switchEmailForward.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("EMAIL_FORWARDING", isChecked).apply()
            refreshReadiness()
        }

        binding.switchSmsForward.isChecked = prefs.getBoolean("SMS_FORWARDING", true)
        binding.switchSmsForward.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("SMS_FORWARDING", isChecked).apply()
            updateSimCardVisibility(isChecked)
            if (isChecked) requestSmsPermissions()
            refreshReadiness()
        }

        // ── SIM Selector (conditional) ──
        updateSimCardVisibility(prefs.getBoolean("SMS_FORWARDING", true))

        // ── Recipients ──
        refreshEmailList()
        refreshPhoneList()
        binding.btnAddEmail.setOnClickListener { showAddRecipientDialog("Email address", "email") }
        binding.btnAddPhone.setOnClickListener { showAddRecipientDialog("Phone number (with country code)", "phone") }

        // ── Test ──
        binding.btnTestEmail.setOnClickListener { runTestEmail() }
        binding.btnTestSms.setOnClickListener { runTestSms() }

        // ── Battery ──
        binding.btnBatteryOpt.setOnClickListener {
            BatteryOptimizationHelper.requestUnrestrictedBattery(this)
        }
        binding.btnOemAutostart.setOnClickListener {
            BatteryOptimizationHelper.openOemAutostartSettings(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.btnNotificationPerm.visibility = View.VISIBLE
            binding.btnNotificationPerm.setOnClickListener {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // ── Theme Toggle ──
        setupThemeToggle()

        // ── Reset ──
        binding.btnResetAll.setOnClickListener { showResetDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
        refreshSimDropdown()
        refreshLog()
        refreshEmailList()
        refreshPhoneList()
        updateEmailStatus()
    }

    // ── Readiness Checklist ──

    private fun refreshReadiness() {
        val checks = mutableListOf<Pair<String, Boolean>>()

        val smsPermsOk = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        checks.add("SMS permissions" to smsPermsOk)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            checks.add("Notification permission" to notifOk)
        }

        val emailOn = prefs.getBoolean("EMAIL_FORWARDING", true)
        if (emailOn) {
            val method = prefs.getString("EMAIL_METHOD", "smtp")
            if (method == "smtp") {
                checks.add("Email configured" to SmtpHelper.isConfigured(this))
            } else {
                checks.add("Gmail signed in" to GmailHelper.isSignedIn(this))
            }
            checks.add("Email recipients added" to RecipientManager.getEmails(this).isNotEmpty())
        }

        val smsOn = prefs.getBoolean("SMS_FORWARDING", true)
        if (smsOn) {
            checks.add("SMS recipients added" to RecipientManager.getPhones(this).isNotEmpty())
        }

        val pm = getSystemService(PowerManager::class.java)
        checks.add("Battery optimization off" to pm.isIgnoringBatteryOptimizations(packageName))

        checks.add("Master toggle on" to prefs.getBoolean("IS_ENABLED", false))

        val passed = checks.count { it.second }
        val total = checks.size
        val allOk = passed == total

        binding.textReadinessTitle.text = if (allOk) "Ready" else "$passed of $total checks passed"
        if (allOk) {
            binding.cardReadiness.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.md_theme_primaryContainer)
            )
        } else {
            binding.cardReadiness.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.md_theme_surfaceContainer)
            )
        }

        binding.containerChecklist.removeAllViews()
        for ((label, ok) in checks) {
            binding.containerChecklist.addView(checklistRow(label, ok))
        }
    }

    private fun checklistRow(label: String, ok: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(3), 0, dpToPx(3))

            addView(TextView(this@MainActivity).apply {
                text = if (ok) "\u2713" else "\u2717"
                setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (ok) R.color.status_signed_in else R.color.remove_button))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, dpToPx(10), 0)
            })

            addView(TextView(this@MainActivity).apply {
                text = label
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                alpha = if (ok) 0.7f else 1.0f
            })
        }
    }

    // ── SIM Selector ──

    private fun updateSimCardVisibility(smsOn: Boolean) {
        binding.cardSimSelector.visibility = if (smsOn) View.VISIBLE else View.GONE
    }

    private fun refreshSimDropdown() {
        if (binding.cardSimSelector.visibility != View.VISIBLE) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)
                || !prefs.getBoolean("PHONE_STATE_PERM_ASKED", false)
            ) {
                prefs.edit().putBoolean("PHONE_STATE_PERM_ASKED", true).apply()
                phoneStatePermLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
            return
        }

        val sims = SimManager.getAvailableSims(this)
        simOptions = listOf(-1 to "System Default") + sims.map { it.subscriptionId to it.label }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line,
            simOptions.map { it.second })
        binding.dropdownSim.setAdapter(adapter)

        val currentSubId = SimManager.getPreferredSubId(this)
        val selectedLabel = simOptions.firstOrNull { it.first == currentSubId }?.second ?: "System Default"
        binding.dropdownSim.setText(selectedLabel, false)

        binding.dropdownSim.setOnItemClickListener { _, _, position, _ ->
            val subId = simOptions.getOrNull(position)?.first ?: -1
            SimManager.setPreferredSubId(this, subId)
        }
    }

    // ── Email method / status ──

    private fun setupEmailMethodSelector() {
        val methods = listOf("App Password (Simple)", "OAuth2 (Advanced)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, methods)
        binding.dropdownEmailMethod.setAdapter(adapter)

        val current = prefs.getString("EMAIL_METHOD", "smtp")
        binding.dropdownEmailMethod.setText(
            if (current == "smtp") methods[0] else methods[1], false
        )
        updateEmailMethodSections(current ?: "smtp")

        binding.dropdownEmailMethod.setOnItemClickListener { _, _, position, _ ->
            val method = if (position == 0) "smtp" else "oauth2"
            prefs.edit().putString("EMAIL_METHOD", method).apply()
            updateEmailMethodSections(method)
            updateEmailStatus()
            refreshReadiness()
        }
    }

    private fun updateEmailMethodSections(method: String) {
        if (method == "smtp") {
            binding.sectionAppPassword.visibility = View.VISIBLE
            binding.sectionOauth.visibility = View.GONE
        } else {
            binding.sectionAppPassword.visibility = View.GONE
            binding.sectionOauth.visibility = View.VISIBLE
        }
    }

    private fun updateEmailStatus() {
        val method = prefs.getString("EMAIL_METHOD", "smtp")
        val dot = binding.gmailStatusDot.background?.mutate() as? GradientDrawable

        if (method == "smtp") {
            val email = SmtpHelper.getConfiguredEmail(this)
            if (email != null) {
                binding.textGmailStatus.text = email
                dot?.setColor(ContextCompat.getColor(this, R.color.status_signed_in))
            } else {
                binding.textGmailStatus.text = "Not configured"
                dot?.setColor(ContextCompat.getColor(this, R.color.status_signed_out))
            }
        } else {
            val email = GmailHelper.getSignedInEmail(this)
            if (email != null) {
                binding.textGmailStatus.text = email
                dot?.setColor(ContextCompat.getColor(this, R.color.status_signed_in))
            } else {
                binding.textGmailStatus.text = "Not signed in"
                dot?.setColor(ContextCompat.getColor(this, R.color.status_signed_out))
            }
        }
    }

    private fun showSmtpConfigDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), 0)
        }

        val emailInput = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "Gmail address"
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dpToPx(12)
            }
        }
        val emailEdit = TextInputEditText(emailInput.context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(SmtpHelper.getConfiguredEmail(this@MainActivity) ?: "")
        }
        emailInput.addView(emailEdit)

        val passInput = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "App Password (16 characters)"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val passEdit = TextInputEditText(passInput.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passInput.addView(passEdit)

        container.addView(emailInput)
        container.addView(passInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("Gmail App Password")
            .setMessage("Enable 2-Step Verification on your Google account, then generate an App Password at myaccount.google.com > Security > App Passwords.\n\nOr visit directly:\nhttps://myaccount.google.com/apppasswords")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val email = emailEdit.text.toString().trim()
                val pass = passEdit.text.toString().trim()
                if (email.isBlank() || pass.isBlank()) {
                    snack("Both fields are required")
                    return@setPositiveButton
                }
                SmtpHelper.saveCredentials(this, email, pass)
                updateEmailStatus()
                refreshReadiness()
                snack("SMTP credentials saved")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                SmtpHelper.clearCredentials(this)
                updateEmailStatus()
                refreshReadiness()
                snack("SMTP credentials cleared")
            }
            .show()
    }

    // ── Recipient lists ──

    private fun refreshEmailList() {
        binding.containerEmails.removeAllViews()
        val emails = RecipientManager.getEmails(this)
        if (emails.isEmpty()) {
            binding.containerEmails.addView(emptyHint("No email recipients yet"))
        } else {
            emails.forEach { email ->
                binding.containerEmails.addView(recipientRow(email) {
                    RecipientManager.removeEmail(this, email)
                    refreshEmailList()
                    refreshReadiness()
                })
            }
        }
    }

    private fun refreshPhoneList() {
        binding.containerPhones.removeAllViews()
        val phones = RecipientManager.getPhones(this)
        if (phones.isEmpty()) {
            binding.containerPhones.addView(emptyHint("No SMS recipients yet"))
        } else {
            phones.forEach { phone ->
                binding.containerPhones.addView(recipientRow(phone) {
                    RecipientManager.removePhone(this, phone)
                    refreshPhoneList()
                    refreshReadiness()
                })
            }
        }
    }

    private fun emptyHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            alpha = 0.5f
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
    }

    private fun recipientRow(value: String, onRemove: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(6), 0, dpToPx(6))

            addView(TextView(this@MainActivity).apply {
                this.text = value
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })

            addView(com.google.android.material.button.MaterialButton(
                this@MainActivity, null,
                com.google.android.material.R.attr.materialIconButtonStyle
            ).apply {
                text = ""
                icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_close_clear_cancel)
                iconTint = ContextCompat.getColorStateList(this@MainActivity, R.color.remove_button)
                setOnClickListener { onRemove() }
            })
        }
    }

    private fun showAddRecipientDialog(hint: String, type: String) {
        val inputLayout = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(dpToPx(24), dpToPx(16), dpToPx(24), 0)
            }
            this.hint = hint
        }

        val editText = TextInputEditText(inputLayout.context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            inputType = if (type == "email")
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else
                android.text.InputType.TYPE_CLASS_PHONE
        }
        inputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add ${if (type == "email") "Email" else "SMS"} Recipient")
            .setView(inputLayout)
            .setPositiveButton("Add") { _, _ ->
                val value = editText.text.toString().trim()
                if (value.isEmpty()) { snack("Input is empty"); return@setPositiveButton }
                val added = if (type == "email") RecipientManager.addEmail(this, value)
                    else RecipientManager.addPhone(this, value)
                if (added) {
                    if (type == "email") refreshEmailList() else refreshPhoneList()
                    snack("Added: $value")
                    refreshReadiness()
                } else {
                    snack(if (type == "email") "Invalid email or already exists" else "Invalid phone number or already exists")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Forwarding Log ──

    private fun refreshLog() {
        binding.containerLog.removeAllViews()
        val entries = ForwardingLog.getEntries(this)
        if (entries.isEmpty()) {
            binding.containerLog.addView(emptyHint("No forwarding activity yet"))
            return
        }
        val toShow = entries.take(10)
        for (entry in toShow) {
            binding.containerLog.addView(logRow(entry))
        }
    }

    private fun logRow(entry: LogEntry): LinearLayout {
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))

            addView(TextView(this@MainActivity).apply {
                text = if (entry.success) "\u2713" else "\u2717"
                setTextColor(ContextCompat.getColor(this@MainActivity,
                    if (entry.success) R.color.status_signed_in else R.color.remove_button))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, dpToPx(8), 0)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

                val desc = buildString {
                    append(entry.channel.uppercase())
                    if (entry.extractedOtp != null) append(" OTP:${entry.extractedOtp}")
                    append(" from ${entry.sender}")
                }

                addView(TextView(this@MainActivity).apply {
                    text = desc
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = relativeTime
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                    alpha = 0.6f
                })
            })
        }
    }

    // ── Test functions ──

    private fun runTestEmail() {
        val emails = RecipientManager.getEmails(this)
        if (emails.isEmpty()) { snack("No email recipients configured"); return }

        val method = prefs.getString("EMAIL_METHOD", "smtp")
        if (method == "smtp" && !SmtpHelper.isConfigured(this)) {
            snack("Please configure App Password first"); return
        }
        if (method == "oauth2" && !GmailHelper.isSignedIn(this)) {
            snack("Please sign in to Gmail first"); return
        }

        snack("Sending test email...")
        lifecycleScope.launch {
            var allOk = true
            for (email in emails) {
                val ok = if (method == "smtp") {
                    SmtpHelper.sendEmail(this@MainActivity, email,
                        "TextSling - Test",
                        "This is a test email from TextSling.\n\nIf you received this, email forwarding is working correctly.")
                } else {
                    GmailHelper.sendEmail(this@MainActivity, email,
                        "TextSling - Test",
                        "This is a test email from TextSling.\n\nIf you received this, email forwarding is working correctly.")
                }
                if (!ok) allOk = false
            }
            withContext(Dispatchers.Main) {
                snack(if (allOk) "Test email sent successfully" else "Some emails failed to send")
            }
        }
    }

    private fun runTestSms() {
        val phones = RecipientManager.getPhones(this)
        if (phones.isEmpty()) { snack("No SMS recipients configured"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            sendSmsPermLauncher.launch(Manifest.permission.SEND_SMS); return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val smsManager = SimManager.getSmsManager(this@MainActivity)
            var allOk = true
            for (phone in phones) {
                try {
                    smsManager.sendTextMessage(phone, null,
                        "TextSling Test: If you received this, SMS forwarding is working.",
                        null, null)
                } catch (e: Exception) { allOk = false }
            }
            withContext(Dispatchers.Main) {
                snack(if (allOk) "Test SMS sent" else "Some SMS failed to send")
            }
        }
    }

    // ── Reset ──

    private fun showResetDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset All Settings")
            .setMessage("This will sign out of Gmail, remove all recipients, clear the forwarding log, and reset all settings to defaults.\n\nContinue?")
            .setPositiveButton("Reset") { _, _ -> performReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReset() {
        prefs.edit().clear().apply()
        RecipientManager.clearAll(this)
        ForwardingLog.clear(this)
        SmtpHelper.clearCredentials(this)
        GmailHelper.signOut(this) {}

        suppressListeners = true
        binding.switchMaster.isChecked = false
        binding.switchForwardAll.isChecked = false
        binding.switchEmailForward.isChecked = true
        binding.switchSmsForward.isChecked = true
        suppressListeners = false

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        binding.toggleTheme.check(R.id.btnThemeSystem)

        setupEmailMethodSelector()
        updateSimCardVisibility(true)
        updateEmailStatus()
        refreshSimDropdown()
        refreshEmailList()
        refreshPhoneList()
        refreshLog()
        refreshReadiness()
        snack("All settings reset to defaults")
    }

    // ── Permissions ──

    private fun requestSmsPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECEIVE_SMS)
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_SMS)
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.SEND_SMS)
        if (needed.isNotEmpty()) smsPermissionLauncher.launch(needed.toTypedArray())
    }

    // ── Theme Toggle ──

    private fun setupThemeToggle() {
        val current = prefs.getInt(ForwarderApplication.THEME_PREF_KEY, ForwarderApplication.THEME_SYSTEM)
        binding.toggleTheme.check(
            when (current) {
                ForwarderApplication.THEME_LIGHT -> R.id.btnThemeLight
                ForwarderApplication.THEME_DARK -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
        )

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> ForwarderApplication.THEME_LIGHT
                R.id.btnThemeDark -> ForwarderApplication.THEME_DARK
                else -> ForwarderApplication.THEME_SYSTEM
            }
            prefs.edit().putInt(ForwarderApplication.THEME_PREF_KEY, mode).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ForwarderApplication.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ForwarderApplication.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    // ── Collapsible Sections ──

    private fun setupCollapsibleSections() {
        makeCollapsible(binding.textReadinessTitle, binding.containerChecklist)
        makeCollapsible(binding.titleBatteryBackground, binding.contentBatteryBackground)
        makeCollapsible(binding.titleEmailAccount, binding.contentEmailAccount)
        makeCollapsible(binding.titleForwardingSettings, binding.contentForwardingSettings)
        makeCollapsible(binding.titleEmailRecipients, binding.contentEmailRecipients)
        makeCollapsible(binding.titleSmsRecipients, binding.contentSmsRecipients)
        makeCollapsible(binding.titleTestDelivery, binding.contentTestDelivery)
        makeCollapsible(binding.titleRecentActivity, binding.contentRecentActivity)
    }

    private fun makeCollapsible(titleView: TextView, contentView: View) {
        fun setArrow(expanded: Boolean) {
            val arrowRes = if (expanded) android.R.drawable.arrow_up_float
                           else android.R.drawable.arrow_down_float
            val arrow = ContextCompat.getDrawable(this, arrowRes)?.mutate()
            arrow?.setTint(titleView.currentTextColor)
            titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        }

        setArrow(true)
        titleView.compoundDrawablePadding = dpToPx(6)
        titleView.minimumHeight = dpToPx(48)
        titleView.gravity = Gravity.CENTER_VERTICAL
        titleView.setOnClickListener {
            val expanding = contentView.visibility != View.VISIBLE
            contentView.visibility = if (expanding) View.VISIBLE else View.GONE
            setArrow(expanding)
        }
    }

    // ── Utilities ──

    private fun snack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
