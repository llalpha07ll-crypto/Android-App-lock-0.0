package com.example.applock

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager

class MainActivity : AppCompatActivity() {
    private data class AppItem(val label: String, val pkg: String, val icon: Drawable)
    private class Row(val item: AppItem, val view: View, val toggle: SwitchCompat)

    private val rows = mutableListOf<Row>()
    private lateinit var content: View
    private lateinit var statusText: TextView
    private lateinit var credentialStatus: TextView
    private lateinit var durationSummary: TextView
    private lateinit var lockedCount: TextView
    private lateinit var emptyText: TextView
    private lateinit var appContainer: LinearLayout
    private lateinit var biometricSwitch: SwitchCompat
    private lateinit var selfLockSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        statusText = findViewById(R.id.status)
        credentialStatus = findViewById(R.id.credentialStatus)
        durationSummary = findViewById(R.id.durationSummary)
        lockedCount = findViewById(R.id.lockedCount)
        emptyText = findViewById(R.id.emptyText)
        appContainer = findViewById(R.id.appContainer)
        biometricSwitch = findViewById(R.id.biometricSwitch)
        selfLockSwitch = findViewById(R.id.selfLockSwitch)

        findViewById<Button>(R.id.enableButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.rowScreenLock).setOnClickListener { showCredentialSetup() }
        findViewById<View>(R.id.rowDuration).setOnClickListener { showDurationDialog() }

        biometricSwitch.isChecked = LockPrefs.biometricEnabled(this)
        biometricSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !LockPrefs.hasCredential(this)) {
                biometricSwitch.isChecked = false
                toast("Set a PIN, pattern or password first")
            } else if (checked && BiometricManager.from(this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS
            ) {
                biometricSwitch.isChecked = false
                toast("No fingerprint is enrolled on this phone")
            } else {
                LockPrefs.setBiometricEnabled(this, checked)
            }
        }

        selfLockSwitch.isChecked = LockPrefs.isSelfLocked(this)
        selfLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !LockPrefs.hasCredential(this)) {
                selfLockSwitch.isChecked = false
                toast("Set a PIN, pattern or password first")
            } else {
                LockPrefs.setSelfLocked(this, checked)
            }
        }

        findViewById<EditText>(R.id.searchApps).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<Button>(R.id.lockAllButton).setOnClickListener { confirmLockAll(true) }
        findViewById<Button>(R.id.unlockAllButton).setOnClickListener { confirmLockAll(false) }

        Thread {
            val apps = loadApps()
            runOnUiThread { buildRows(apps) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        durationSummary.text = formatDelay(LockPrefs.getRelockDelay(this))
        credentialStatus.text =
            if (LockPrefs.hasCredential(this)) typeLabel(LockPrefs.getType(this)) else "Not set"
        refreshToggles()

        // Lock this app itself: hide the content until the user unlocks.
        val needsUnlock = LockPrefs.hasCredential(this) &&
            LockPrefs.isSelfLocked(this) &&
            !UnlockState.isUnlocked(packageName)
        content.visibility = if (needsUnlock) View.INVISIBLE else View.VISIBLE
        if (needsUnlock && AppLockService.canLaunchLock()) {
            startActivity(Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_PKG, packageName))
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) UnlockState.left(this, packageName)
    }

    // ---------- Status ----------
    private fun updateStatus() {
        statusText.setText(
            when {
                !LockPrefs.hasCredential(this) -> R.string.status_no_lock
                isServiceEnabled() -> R.string.status_on
                else -> R.string.status_off
            }
        )
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    // ---------- App list ----------
    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun buildRows(apps: List<AppItem>) {
        rows.clear()
        appContainer.removeAllViews()
        for (item in apps) {
            val view = layoutInflater.inflate(R.layout.item_app, appContainer, false)
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.name).text = item.label
            val toggle = view.findViewById<SwitchCompat>(R.id.lockSwitch)
            toggle.isChecked = item.pkg in LockPrefs.getLocked(this)
            toggle.setOnCheckedChangeListener { _, checked ->
                LockPrefs.setLocked(this, item.pkg, checked)
                updateLockedCount()
            }
            view.setOnClickListener { toggle.toggle() }
            appContainer.addView(view)
            rows.add(Row(item, view, toggle))
        }
        updateLockedCount()
        filterApps(findViewById<EditText>(R.id.searchApps).text.toString())
    }

    private fun refreshToggles() {
        val locked = LockPrefs.getLocked(this)
        for (row in rows) {
            val want = row.item.pkg in locked
            if (row.toggle.isChecked != want) row.toggle.isChecked = want
        }
        updateLockedCount()
    }

    private fun updateLockedCount() {
        val locked = LockPrefs.getLocked(this)
        val n = rows.count { it.item.pkg in locked }
        lockedCount.text = if (n == 1) "1 app locked" else "$n apps locked"
    }

    private fun filterApps(query: String) {
        var shown = 0
        for (row in rows) {
            val match = query.isBlank() ||
                row.item.label.contains(query, ignoreCase = true) ||
                row.item.pkg.contains(query, ignoreCase = true)
            row.view.visibility = if (match) View.VISIBLE else View.GONE
            if (match) shown++
        }
        emptyText.visibility = if (rows.isNotEmpty() && shown == 0) View.VISIBLE else View.GONE
    }

    private fun confirmLockAll(lock: Boolean) {
        if (rows.isEmpty()) return
        if (lock && !LockPrefs.hasCredential(this)) {
            toast("Set a PIN, pattern or password first")
            return
        }
        val title = if (lock) "Lock all apps?" else "Unlock all apps?"
        val message = if (lock) {
            "All ${rows.size} apps on this list will ask for your lock before opening."
        } else {
            "Protection will be removed from all apps on this list."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (lock) "Lock all" else "Unlock all") { _, _ ->
                LockPrefs.setAllLocked(this, rows.map { it.item.pkg }, lock)
                refreshToggles()
            }
            .show()
    }

    // ---------- Unlock duration ----------
    private fun formatDelay(sec: Int): String = when {
        sec < 0 -> "Until the screen turns off"
        sec == 0 -> "Lock immediately after leaving the app"
        sec < 60 -> "$sec seconds"
        sec % 3600 == 0 -> if (sec == 3600) "1 hour" else "${sec / 3600} hours"
        sec < 3600 && sec % 60 == 0 -> if (sec == 60) "1 minute" else "${sec / 60} minutes"
        else -> "${sec / 60} min ${sec % 60} s"
    }

    private fun showDurationDialog() {
        val labels = arrayOf("Immediately", "15 seconds", "30 seconds", "1 minute", "5 minutes", "Until the screen turns off", "Custom…")
        val values = intArrayOf(0, 15, 30, 60, 300, LockPrefs.DELAY_UNTIL_SCREEN_OFF)
        val index = values.indexOf(LockPrefs.getRelockDelay(this))
        val checked = if (index >= 0) index else labels.size - 1
        AlertDialog.Builder(this)
            .setTitle("Unlock duration")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (which < values.size) setDelay(values[which]) else showCustomDuration()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomDuration() {
        val input = EditText(this).apply {
            hint = "Time"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val unit = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Seconds", "Minutes", "Hours")
            )
            setSelection(1)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(input)
            addView(unit)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom unlock duration")
            .setMessage("App stays unlocked for this long after you leave it.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().toLongOrNull()
                val multiplier = longArrayOf(1L, 60L, 3600L)[unit.selectedItemPosition]
                val total = if (n == null) 0L else n * multiplier
                if (total < 1L || total > LockPrefs.MAX_DELAY_SECONDS) {
                    toast("Enter a time between 1 second and 24 hours")
                } else {
                    setDelay(total.toInt())
                }
            }
            .show()
    }

    private fun setDelay(seconds: Int) {
        LockPrefs.setRelockDelay(this, seconds)
        durationSummary.text = formatDelay(seconds)
    }

    // ---------- Lock method setup ----------
    private fun showCredentialSetup() {
        val choices = arrayOf("Phone screen lock (system screen)", "Own PIN", "Own pattern", "Own password")
        val types = arrayOf(LockPrefs.TYPE_SYSTEM, "PIN", "Pattern", "Password")
        AlertDialog.Builder(this).setTitle("Choose lock method")
            .setItems(choices) { _, which ->
                val type = types[which]
                if (type == LockPrefs.TYPE_SYSTEM) {
                    setupSystemLock()
                    return@setItems
                }
                if (type == "Pattern") {
                    showPatternSetup()
                    return@setItems
                }
                val input = EditText(this).apply {
                    hint = if (type == "PIN") "Enter 4–8 digit PIN" else "Enter password (8+ characters)"
                    inputType = if (type == "PIN") {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                }
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 8, 48, 0)
                    addView(input)
                }
                AlertDialog.Builder(this).setTitle("Set $type").setView(box)
                    .setPositiveButton("Continue") { _, _ ->
                        val value = input.text.toString()
                        val valid = if (type == "PIN") value.matches(Regex("[0-9]{4,8}")) else value.length >= 8
                        if (!valid) {
                            toast("Invalid $type. Please try again.")
                        } else {
                            AlertDialog.Builder(this).setTitle("Confirm $type")
                                .setMessage("Save this as your App Lock $type?")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Save") { _, _ ->
                                    LockPrefs.setCredential(this, type, value)
                                    onCredentialSaved(type)
                                }.show()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun typeLabel(type: String): String =
        if (type == LockPrefs.TYPE_SYSTEM) "Phone screen lock" else type

    private fun setupSystemLock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            toast("First set a PIN, pattern or password in your phone's Settings")
            return
        }
        LockPrefs.setSystemCredential(this)
        onCredentialSaved(LockPrefs.TYPE_SYSTEM)
    }

    private fun onCredentialSaved(type: String) {
        credentialStatus.text = typeLabel(type)
        updateStatus()
        // The user just proved they know the credential by setting it; don't lock them out right away.
        UnlockState.markOpen(packageName)
        toast("${typeLabel(type)} saved")
    }

    private fun showPatternSetup() {
        val pattern = PatternView(this)
        val hint = TextView(this).apply {
            text = "Draw an unlock pattern using at least 4 dots"
            textSize = 15f
            setPadding(8, 12, 8, 8)
        }
        val clear = Button(this).apply { text = "Clear"; setOnClickListener { pattern.clearPattern() } }
        val box = patternBox(hint, pattern, clear)
        val dialog = AlertDialog.Builder(this).setTitle("Draw pattern").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = pattern.pattern()
                if (!pattern.isValidPattern()) {
                    hint.text = "Connect at least 4 different dots"
                    return@setOnClickListener
                }
                dialog.dismiss()
                confirmPatternSetup(first)
            }
        }
        dialog.show()
    }

    private fun confirmPatternSetup(first: String) {
        val pattern = PatternView(this)
        val hint = TextView(this).apply {
            text = "Draw the same pattern again to confirm"
            textSize = 15f
            setPadding(8, 12, 8, 8)
        }
        val clear = Button(this).apply { text = "Clear"; setOnClickListener { pattern.clearPattern() } }
        val box = patternBox(hint, pattern, clear)
        val dialog = AlertDialog.Builder(this).setTitle("Confirm pattern").setView(box)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!pattern.isValidPattern()) {
                    hint.text = "Connect at least 4 different dots"
                    return@setOnClickListener
                }
                if (pattern.pattern() != first) {
                    hint.text = "Patterns do not match. Clear and try again."
                    pattern.clearPattern()
                    return@setOnClickListener
                }
                LockPrefs.setCredential(this, "Pattern", first)
                onCredentialSaved("Pattern")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun patternBox(hint: TextView, pattern: PatternView, clear: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(hint)
            addView(
                pattern,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (300 * resources.displayMetrics.density).toInt()
                )
            )
            addView(clear)
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
