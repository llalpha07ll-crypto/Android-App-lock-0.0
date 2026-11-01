package com.example.applock

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which apps are currently unlocked.
 * A value of OPEN means the app is in the foreground right now; any other value is the
 * time (elapsedRealtime) until which the app stays unlocked after the user left it.
 */
object UnlockState {
    private const val OPEN = Long.MAX_VALUE
    private val map = ConcurrentHashMap<String, Long>()

    fun markOpen(pkg: String) {
        map[pkg] = OPEN
    }

    fun isUnlocked(pkg: String): Boolean {
        val until = map[pkg] ?: return false
        if (until == OPEN || SystemClock.elapsedRealtime() < until) return true
        map.remove(pkg)
        return false
    }

    /** Call when the user leaves [pkg]. Starts the unlock-duration countdown. */
    fun left(ctx: Context, pkg: String) {
        if (map[pkg] != OPEN) return
        val delay = LockPrefs.getRelockDelay(ctx)
        when {
            delay < 0 -> Unit // stays unlocked until the screen turns off
            delay == 0 -> map.remove(pkg)
            else -> map[pkg] = SystemClock.elapsedRealtime() + delay * 1000L
        }
    }

    fun clear() {
        map.clear()
    }
}
