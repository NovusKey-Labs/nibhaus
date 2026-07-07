package com.nibhaus.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Opt-in vault lock (Section C1): a biometric prompt with device-credential (PIN/pattern/password)
 * fallback, so it works on any device that has *some* secure lock.
 *
 * On API 30+, the prompt is bound to a Keystore [BiometricPrompt.CryptoObject] (a gate-only key,
 * see [gateCipher]) so success is a real cryptographic unlock, not just a boolean callback — a bare
 * PromptInfo with no CryptoObject can be spoofed by hooking onAuthenticationSucceeded (e.g. via
 * Frida) since nothing forces a genuine crypto operation to happen first. Below API 30,
 * CryptoObject-based auth can't be combined with the DEVICE_CREDENTIAL fallback this screen relies
 * on — the platform throws IllegalArgumentException for that combination pre-R — so those devices
 * keep the boolean-only prompt; that's a platform limitation, not a downgrade chosen here.
 *
 * When the encrypted DB (C2) lands, swap this gate key for the SQLCipher passphrase's key so the
 * same unlock also unlocks the DB.
 */
object AppLock {
    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    private const val GATE_ALIAS = "nibhaus_applock_gate"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** True only if the device can actually authenticate (a biometric or device credential is set up). */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Show the system unlock prompt; [onResult] is true on success, false on error/cancel. */
    fun prompt(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                // onAuthenticationFailed = one bad attempt; the prompt stays up, so we don't act on it.
            },
        )
        // Note: with DEVICE_CREDENTIAL allowed, the API forbids a negative button, so we set none.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Nibhaus")
            .setSubtitle("Verify it's you to open your notes")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        // CryptoObject + DEVICE_CREDENTIAL is only legal on API 30+ (the platform throws
        // IllegalArgumentException below that level). Below 30, fall back to the boolean-only
        // prompt so the device-credential path this screen depends on keeps working.
        val cipher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) gateCipher() else null
        if (cipher != null) {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info)
        }
    }

    /**
     * A fresh [Cipher] over the gate-only Keystore key (API 30+ only), or null if it couldn't be
     * prepared — the caller falls back to the boolean-only prompt in that case. The key requires
     * authentication for every use (no validity window: see [generateGateKey]) so each call demands
     * a real biometric/device-credential check, not a cached unlock from an earlier prompt.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun gateCipher(): Cipher? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(GATE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateGateKey()
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }.getOrElse { e ->
        if (e is KeyPermanentlyInvalidatedException) {
            // Enrollment changed (new fingerprint/PIN) since the key was made: it's dead. Drop it so
            // the next call regenerates a fresh one instead of failing on every prompt from here on.
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(GATE_ALIAS) }
        }
        null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun generateGateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    GATE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0, // require the check on every single use, not a cached window
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    .build(),
            )
        }.generateKey()
}
