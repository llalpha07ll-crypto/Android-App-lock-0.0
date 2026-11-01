package com.example.applock

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.os.CancellationSignal
import kotlin.math.max

/**
 * Lock prompt for a protected app. Shows a card with the locked app's own icon and name,
 * fingerprint/face unlock, and a button to use the PIN instead.
 */
class LockActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PKG = "target_pkg"
        private const val MAX_PIN = 8
    }

    private var targetPkg = ""
    private var appName = ""
    private var finished = false
    private var awaitingSystem = false
    private var bioAvailable = false
    private var customBuilt = false
    private val entered = StringBuilder()
    private var fpCancel: CancellationSignal? = null

    private lateinit var card: View
    private lateinit var customLayout: View
    private lateinit var hintView: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var inputArea: FrameLayout
    private lateinit var cardDesc: TextView

    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingSystem = false
            if (result.resultCode == RESULT_OK) unlockAndClose() else goHome()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        targetPkg = intent.getStringExtra(EXTRA_PKG).orEmpty()

        if (!LockPrefs.hasCredential(this)) {
            finished = true
            finish()
            return
        }

        setContentView(R.layout.activity_lock)
        appName = appLabel()
        val icon: Drawable? = try { packageManager.getApplicationIcon(targetPkg) } catch (_: Exception) { null }

        card = findViewById(R.id.card)
        customLayout = findViewById(R.id.customLayout)
        hintView = findViewById(R.id.lockHint)
        dotsRow = findViewById(R.id.dotsRow)
        inputArea = findViewById(R.id.inputArea)
        cardDesc = findViewById(R.id.cardDesc)

        // The locked app's own icon and name (never App Lock's).
        findViewById<ImageView>(R.id.cardIcon).setImageDrawable(icon)
        findViewById<TextView>(R.id.cardAppName).text = appName
        findViewById<ImageView>(R.id.lockIcon).setImageDrawable(icon)
        findViewById<TextView>(R.id.lockAppName).text = appName
        findViewById<TextView>(R.id.cardTitle).text = "Unlock $appName"

        val type = LockPrefs.getType(this)
        val useLabel = when (type) {
            "Pattern" -> "Use pattern"
            "Password" -> "Use password"
            "PIN" -> "Use PIN"
            else -> "Use PIN"
        }
        val usePin = findViewById<TextView>(R.id.cardUsePin)
        usePin.text = useLabel
        usePin.setOnClickListener { proceedToCredential() }

        bioAvailable = LockPrefs.biometricEnabled(this) &&
            BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

        // No intermediate card: go straight to fingerprint/face, or to the PIN screen.
        card.visibility = View.GONE
        window.decorView.post {
            if (bioAvailable) showBiometricPrompt() else proceedToCredential()
        }
    }

    override fun onResume() {
        super.onResume()
        AppLockService.lockActive = true
    }

    override fun onPause() {
        AppLockService.lockActive = false
        fpCancel?.cancel()
        fpCancel = null
        super.onPause()
    }

    // ---------- Fingerprint / face ----------
    private fun showBiometricPrompt() {
        if (finished) return
        fpCancel?.cancel()
        fpCancel = null
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockAndClose()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> proceedToCredential()
                        else -> goHome()
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock $appName")
            .setSubtitle("Use your fingerprint or face")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use PIN")
            .build()
        prompt.authenticate(info)
    }

    // ---------- PIN / pattern / password ----------
    private fun proceedToCredential() {
        if (finished) return
        if (LockPrefs.getType(this) == LockPrefs.TYPE_SYSTEM) {
            launchSystemCredential()
        } else {
            showCustomCredential()
        }
    }

    /** Opens the phone's own PIN/pattern/password screen. */
    private fun launchSystemCredential() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val intent = km.createConfirmDeviceCredentialIntent(
            "Unlock $appName",
            "Confirm your phone lock to access $appName"
        )
        if (intent == null) {
            Toast.makeText(this, "Set a screen lock in your phone's Settings first", Toast.LENGTH_LONG).show()
            goHome()
            return
        }
        awaitingSystem = true
        fpCancel?.cancel()
        fpCancel = null
        credentialLauncher.launch(intent)
    }

    private fun showCustomCredential() {
        card.visibility = View.GONE
        customLayout.visibility = View.VISIBLE
        if (customBuilt) return
        customBuilt = true
        when (LockPrefs.getType(this)) {
            "Pattern" -> buildPattern()
            "Password" -> buildPassword()
            else -> buildPin()
        }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun buildPin() {
        hintView.text = "Enter PIN"
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "←", "0", "OK")
        for (k in keys) {
            val special = k == "←" || k == "OK"
            val key = TextView(this).apply {
                text = k
                textSize = if (special) 17f else 30f
                setTextColor(ContextCompat.getColor(this@LockActivity, R.color.lock_text))
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.key_background_flat)
                isClickable = true
                isFocusable = true
                setOnClickListener { onPinKey(k) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(72)
                height = dp(72)
                setMargins(dp(10), dp(6), dp(10), dp(6))
            }
            grid.addView(key, lp)
        }
        inputArea.addView(
            grid,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            )
        )
        refreshDots()
    }

    private fun onPinKey(k: String) {
        when (k) {
            "←" -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            "OK" -> {
                submitPin()
                return
            }
            else -> {
                if (entered.length < MAX_PIN) entered.append(k)
                // Unlock automatically as soon as the typed digits match.
                if (entered.length >= 4 && LockPrefs.verify(this, entered.toString())) {
                    unlockAndClose()
                    return
                }
            }
        }
        refreshDots()
    }

    private fun submitPin() {
        if (LockPrefs.verify(this, entered.toString())) {
            unlockAndClose()
        } else {
            showError("Wrong PIN. Try again.")
            entered.clear()
            refreshDots()
        }
    }

    private fun refreshDots() {
        dotsRow.removeAllViews()
        val count = max(4, entered.length)
        for (i in 0 until count) {
            val dot = View(this)
            dot.setBackgroundResource(if (i < entered.length) R.drawable.dot_filled else R.drawable.dot_empty)
            val lp = LinearLayout.LayoutParams(dp(12), dp(12))
            lp.setMargins(dp(7), 0, dp(7), 0)
            dotsRow.addView(dot, lp)
        }
    }

    private fun buildPattern() {
        hintView.text = "Draw your pattern"
        dotsRow.visibility = View.GONE
        val pv = PatternView(this)
        pv.setDarkStyle(isNight())
        pv.onComplete = { pattern, valid ->
            if (!valid) {
                showError("Connect at least 4 dots")
                pv.postDelayed({ pv.clearPattern() }, 500)
            } else if (LockPrefs.verify(this, pattern)) {
                unlockAndClose()
            } else {
                showError("Wrong pattern. Try again.")
                pv.postDelayed({ pv.clearPattern() }, 700)
            }
        }
        inputArea.addView(
            pv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun buildPassword() {
        hintView.text = "Enter password"
        dotsRow.visibility = View.GONE
        val input = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val textColor = ContextCompat.getColor(this@LockActivity, R.color.lock_text)
            setTextColor(textColor)
            setHintTextColor(ContextCompat.getColor(this@LockActivity, R.color.lock_text_secondary))
            backgroundTintList = ColorStateList.valueOf(textColor)
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (LockPrefs.verify(this, input.text.toString())) {
                    unlockAndClose()
                } else {
                    input.text.clear()
                    showError("Wrong password. Try again.")
                }
                true
            } else {
                false
            }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(40), dp(16), dp(40), 0)
        inputArea.addView(input, lp)
        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------- Helpers ----------
    private fun showError(message: String) {
        hintView.text = message
        hintView.setTextColor(ContextCompat.getColor(this, R.color.lock_error))
    }

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPkg, 0)).toString()
    } catch (_: Exception) {
        targetPkg
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun unlockAndClose() {
        if (finished) return
        finished = true
        fpCancel?.cancel()
        UnlockState.markOpen(targetPkg)
        finish()
    }

    private fun goHome() {
        if (finished) return
        finished = true
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    @Deprecated("Back returns to the home screen")
    override fun onBackPressed() {
        goHome()
    }

    override fun onStop() {
        super.onStop()
        // If the user leaves without unlocking, close this screen so the target app stays protected.
        if (!finished && !isChangingConfigurations && !awaitingSystem) {
            finished = true
            finish()
        }
    }

    override fun onDestroy() {
        AppLockService.lockActive = false
        super.onDestroy()
    }
}
