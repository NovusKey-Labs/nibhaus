package com.nibhaus.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One secret encrypted at rest with an AES-256-GCM key held in the Android Keystore (hardware-backed
 * when the device has a TEE/StrongBox). The key never leaves the Keystore and the plaintext never
 * touches disk — only {iv : ciphertext} is persisted, in a private SharedPreferences. This backs the
 * "enter once, stored encrypted" pen password: it stays on-device and is never sent anywhere.
 *
 * Native platform crypto on purpose. androidx.security:security-crypto (EncryptedSharedPreferences)
 * was deprecated by Jetpack in 2024, and this needs exactly one key and one value — the JDK crypto
 * APIs + Keystore cover it with no extra dependency. Note: one secret per [name]; if multiple
 * pens ever need distinct passwords, key the stored entries by MAC instead of a fixed [name].
 */
class SecretStore(context: Context, private val name: String) {

    private val prefs =
        context.applicationContext.getSharedPreferences("nibhaus_secrets", Context.MODE_PRIVATE)
    private val alias = "nibhaus_secret_$name"
    private val atKey = "${name}_saved_at" // when the secret was last stored (for expiry)

    /**
     * The stored plaintext, or null if: nothing is stored, the device hasn't been unlocked within
     * the auth-validity window ([UserNotAuthenticatedException] — temporary, try again after the
     * next unlock), or the key was invalidated by an enrollment change
     * ([KeyPermanentlyInvalidatedException] — permanent; the dead entry and alias are cleared so
     * the caller re-prompts for the pen password instead of failing forever on a dead key).
     */
    fun get(): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val parts = blob.split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            Cipher.getInstance(TRANSFORM).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(doFinal(ct), Charsets.UTF_8)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            null
        } catch (e: UserNotAuthenticatedException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Encrypt and persist [value], replacing anything stored before. Records the save time. */
    fun set(value: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).putLong(atKey, System.currentTimeMillis()).apply()
    }

    /** Forget the secret (e.g. the user disabled it, or turned off "remember"). */
    fun clear() = prefs.edit().remove(name).remove(atKey).apply()

    /** Whether a secret is currently stored — cheap, no decryption. */
    fun has(): Boolean = prefs.contains(name)

    /** Whether a secret is stored AND was saved within the last [days] (i.e. not expired). */
    fun savedWithinDays(days: Int): Boolean {
        val savedAt = prefs.getLong(atKey, 0L)
        if (savedAt <= 0L) return false
        return System.currentTimeMillis() - savedAt < days * 24L * 60 * 60 * 1000
    }

    // Hardened (Aikido, 2026-07-07): the key now requires a recently-authenticated device (biometric
    // or device-credential) via setUserAuthenticationRequired(true) — it's no longer usable off an
    // unlocked-but-idle device indefinitely. AUTH_VALIDITY_SECONDS below gives a window so the
    // "remember for 30 days" silent auto-reconnect flow still works: the pen foreground service can
    // decrypt the password as long as the device was unlocked within the window, which covers the
    // normal pick-up-the-phone-and-write flow. Outside the window, get() above returns null on
    // UserNotAuthenticatedException instead of crashing, and the caller falls back to prompting.
    // The secret this protects is a smartpen link password (not an account credential); it's
    // Keystore-encrypted at rest, excluded from backup (see backup_rules.xml /
    // data_extraction_rules.xml), and the opt-in AppLock gates the UI on top of this.
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(
                                AUTH_VALIDITY_SECONDS,
                                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                        }
                    }
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"

        // How long after a device unlock the key stays usable without another prompt. Tuned as a
        // defensible middle ground for "recently unlocked device" (the reconnect service runs
        // shortly after the user picks the phone up), not indefinite background access. This value
        // is a judgment call, not a verified match to any other app's config; re-tune from field QA
        // feedback if legitimate reconnects start getting dropped, or tighten it if that never happens.
        const val AUTH_VALIDITY_SECONDS = 300
    }
}
