package com.example.applock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/** Detects foreground app changes and shows the lock screen for protected apps. */
class AppLockService : AccessibilityService() {
    companion object {
        @Volatile
        var lockShowing: Boolean = false
    }

    private val ignoredPackages = setOf("com.android.systemui", "android")
    private var lastPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // App Lock's own screens (including the lock screen) are handled by the app itself.
        if (pkg == packageName || pkg in ignoredPackages || pkg in imePackages()) return

        val previous = lastPkg
        if (previous != null && previous != pkg) UnlockState.left(this, previous)
        lastPkg = pkg

        if (!LockPrefs.hasCredential(this) || !LockPrefs.isLocked(this, pkg)) return
        if (UnlockState.isUnlocked(pkg)) {
            UnlockState.markOpen(pkg)
            return
        }
        if (!lockShowing) launchLock(pkg)
    }

    private fun launchLock(pkg: String) {
        lockShowing = true
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(LockActivity.EXTRA_PKG, pkg)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            lockShowing = false
        }
    }

    private fun imePackages(): Set<String> {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.map { it.packageName }.toSet()
    }

    override fun onInterrupt() {}
}
