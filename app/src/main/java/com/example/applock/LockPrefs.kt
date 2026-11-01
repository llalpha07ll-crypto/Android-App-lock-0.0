package com.example.applock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object LockPrefs {
    private const val FILE = "applock_prefs"
    private const val KEY_LOCKED = "locked_packages"
    private const val KEY_DELAY = "relock_delay_seconds"
    private const val KEY_TYPE = "primary_type"
    private const val KEY_HASH = "credential_hash"
    private const val KEY_SALT = "credential_salt"
    private const val KEY_BIO = "biometric_enabled"
    private const val KEY_SELF = "lock_self"

    /** Relock delay value meaning "stay unlocked until the screen turns off". */
    const val DELAY_UNTIL_SCREEN_OFF = -1
    const val MAX_DELAY_SECONDS = 24 * 60 * 60

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Locked apps ----
    fun getLocked(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_LOCKED, null)?.toSet() ?: emptySet()

    fun setLocked(ctx: Context, pkg: String, locked: Boolean) {
        val s = getLocked(ctx).toMutableSet()
        if (locked) s.add(pkg) else s.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_LOCKED, s).apply()
    }

    fun setAllLocked(ctx: Context, pkgs: Collection<String>, locked: Boolean) {
        val s = getLocked(ctx).toMutableSet()
        if (locked) s.addAll(pkgs) else s.removeAll(pkgs.toSet())
        prefs(ctx).edit().putStringSet(KEY_LOCKED, s).apply()
    }

    fun isSelfLocked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SELF, true)

    fun setSelfLocked(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SELF, v).apply()
    }

    /** True if opening [pkg] must ask for authentication. */
    fun isLocked(ctx: Context, pkg: String): Boolean =
        if (pkg == ctx.packageName) isSelfLocked(ctx) else pkg in getLocked(ctx)

    // ---- Unlock duration ----
    fun getRelockDelay(ctx: Context): Int = prefs(ctx).getInt(KEY_DELAY, 30)

    fun setRelockDelay(ctx: Context, seconds: Int) {
        prefs(ctx).edit().putInt(KEY_DELAY, seconds).apply()
    }

    // ---- Credential ----
    fun getType(ctx: Context): String = prefs(ctx).getString(KEY_TYPE, "") ?: ""

    fun hasCredential(ctx: Context): Boolean =
        getType(ctx).isNotBlank() && prefs(ctx).contains(KEY_HASH)

    fun setCredential(ctx: Context, type: String, value: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(value, salt)
        prefs(ctx).edit()
            .putString(KEY_TYPE, type)
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(ctx: Context, value: String): Boolean {
        val p = prefs(ctx)
        val s = p.getString(KEY_SALT, null) ?: return false
        val h = p.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(
            derive(value, Base64.decode(s, Base64.NO_WRAP)),
            Base64.decode(h, Base64.NO_WRAP)
        )
    }

    private fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, 120000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ---- Biometric ----
    fun biometricEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BIO, false)

    fun setBiometricEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BIO, v).apply()
    }
}
