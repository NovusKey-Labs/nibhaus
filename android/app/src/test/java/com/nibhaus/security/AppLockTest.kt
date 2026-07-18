package com.nibhaus.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AppLockTest {
    @Test fun cryptoUnlockExecutesAuthenticatedCipher() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        }

        assertTrue(AppLock.cryptoUnlockSucceeded(requiresCrypto = true, cipher = cipher))
    }

    @Test fun cryptoUnlockFailsClosedWithoutUsableCipher() {
        assertFalse(AppLock.cryptoUnlockSucceeded(requiresCrypto = true, cipher = null))
        assertFalse(
            AppLock.cryptoUnlockSucceeded(
                requiresCrypto = true,
                cipher = Cipher.getInstance("AES/GCM/NoPadding"),
            ),
        )
    }

    @Test fun preAndroid30UnlockDoesNotRequireCrypto() {
        assertTrue(AppLock.cryptoUnlockSucceeded(requiresCrypto = false, cipher = null))
    }
}
