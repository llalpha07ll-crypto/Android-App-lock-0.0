package com.example.applock

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlin.math.max

/** Lock screen styled like the system app-lock prompt: shows the locked app's own icon and name. */
class LockActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PKG = "target_pkg"
        private const val MAX_PIN = 8
    }

    private var targetPkg = ""
    private var finished = false
    private val entered = StringBuilder()

    private lateinit var hintView: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var inputArea: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        AppLockService.lockShowing = true
        targetPkg = intent.getStringExtra(EXTRA_PKG).orEmpty()

        if (!LockPrefs.hasCredential(this)) {
            finished = true
            finish()
            return
        }

        setContentView(R.layout.activity_lock)
        hintView = findViewById(R.id.lockHint)
        dotsRow = findViewById(R.id.dotsRow)
        inputArea = findViewById(R.id.inputArea)

        findViewById<TextView>(R.id.lockAppName).text = appLabel()
        findViewById<ImageView>(R.id.lockIcon).setImageDrawable(
            try { packageManager.getApplicationIcon(targetPkg) } catch (_: Exception) { null }
        )

        when (LockPrefs.getType(this)) {
            "Pattern" -> buildPattern()
            "Password" -> buildPassword()
            else -> buildPin()
        }
        setupBiometric()
    }

    // ---------- PIN ----------
    private fun buildPin() {
        hintView.text = "Enter your PIN"
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "←", "0", "OK")
        for (k in keys) {
            val special = k == "←" || k == "OK"
            val key = TextView(this).apply {
                text = k
                textSize = if (special) 18f else 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(if (special) R.drawable.key_background_flat else R.drawable.key_background)
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

    // ---------- Pattern ----------
    private fun buildPattern() {
        hintView.text = "Draw your pattern"
        dotsRow.visibility = View.GONE
        val pv = PatternView(this)
        pv.setDarkStyle(true)
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

    // ---------- Password ----------
    private fun buildPassword() {
        hintView.text = "Enter your password"
        dotsRow.visibility = View.GONE
        val input = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
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

    // ---------- Fingerprint ----------
    private fun setupBiometric() {
        if (!LockPrefs.biometricEnabled(this)) return
        val ok = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        if (!ok) return
        val button = findViewById<TextView>(R.id.fingerprintButton)
        button.visibility = View.VISIBLE
        button.setOnClickListener { showBiometric() }
        window.decorView.post { showBiometric() }
    }

    private fun showBiometric() {
        if (finished) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockAndClose()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(appLabel())
            .setSubtitle("Verify it's you to open this app")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use ${LockPrefs.getType(this).ifBlank { "PIN" }}")
            .build()
        prompt.authenticate(info)
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
        // If the user leaves the lock screen without unlocking, close it so the target app stays protected.
        if (!finished && !isChangingConfigurations) {
            finished = true
            finish()
        }
    }

    override fun onDestroy() {
        AppLockService.lockShowing = false
        super.onDestroy()
    }
}
