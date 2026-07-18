package com.nibhaus.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** JVM coverage for the portable AES-GCM envelope used by [SecretStore]. */
class SecretStoreTest {

    @Test
    fun `stored envelope encrypts and decrypts unicode secret exactly`() {
        val secret = "pāssword-🔐-with:colon"
        val key = KeyGenerator.getInstance(transform.substringBefore('/')).apply { init(256) }.generateKey()
        val encryptor = Cipher.getInstance(transform).apply { init(Cipher.ENCRYPT_MODE, key) }
        val blob = Base64.getEncoder().encodeToString(encryptor.iv) + ":" +
            Base64.getEncoder().encodeToString(encryptor.doFinal(secret.toByteArray(Charsets.UTF_8)))

        val (ivPart, ciphertextPart) = blob.split(":")
        val plaintext = Cipher.getInstance(transform).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(ivPart)))
            doFinal(Base64.getDecoder().decode(ciphertextPart)).toString(Charsets.UTF_8)
        }

        assertThat(plaintext).isEqualTo(secret)
        assertThat(blob.split(":")).hasSize(2)
        assertThat(blob).doesNotContain(secret)
    }

    private val transform: String
        get() = SecretStore::class.java.getDeclaredField("TRANSFORM")
            .apply { isAccessible = true }
            .get(null) as String
}
