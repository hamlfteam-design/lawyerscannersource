package com.personal.docscanner.data.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for backup files.
 *
 * AES-256 in GCM mode: GCM authenticates as well as encrypts, so a file that
 * has been altered — or a wrong password — fails loudly at the end of decryption
 * instead of quietly producing garbage that the restore code would then try to
 * parse.
 *
 * The key comes from the password through PBKDF2 with a random per-file salt.
 * The iteration count is deliberately high: it is the only thing standing
 * between a short password and someone with the file and time to spend.
 *
 * File layout:
 *
 *     "SCANBAK1"   8 bytes   magic, also how the app recognises an encrypted file
 *     salt        16 bytes
 *     iv          12 bytes   GCM's nonce size
 *     ciphertext  rest       includes the 16-byte GCM tag
 */
object BackupCrypto {

    val MAGIC: ByteArray = "SCANBAK1".toByteArray(Charsets.US_ASCII)

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    class WrongPassword(cause: Throwable?) :
        Exception("wrong password, or the file is damaged", cause)

    fun encrypt(source: InputStream, target: OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        }

        target.write(MAGIC)
        target.write(salt)
        target.write(iv)
        // CipherOutputStream writes the GCM tag when it is closed, so the close
        // has to happen before the caller considers the file complete.
        CipherOutputStream(target, cipher).use { source.use { input -> input.copyTo(it) } }
        target.flush()
    }

    fun decrypt(source: InputStream, target: OutputStream, password: CharArray) {
        val magic = ByteArray(MAGIC.size)
        require(source.readFully(magic) && magic.contentEquals(MAGIC)) {
            "this file is not an encrypted backup"
        }
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        require(source.readFully(salt) && source.readFully(iv)) { "the file header is truncated" }

        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        }

        // Not CipherInputStream: it swallows the authentication failure and just
        // ends the stream early, which would look like a truncated backup rather
        // than a wrong password. Doing the update/doFinal by hand surfaces it.
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                cipher.update(buffer, 0, read)?.let { target.write(it) }
            }
            cipher.doFinal()?.let { target.write(it) }
            target.flush()
        } catch (t: javax.crypto.AEADBadTagException) {
            throw WrongPassword(t)
        } catch (t: javax.crypto.BadPaddingException) {
            throw WrongPassword(t)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** InputStream.read is allowed to return fewer bytes than asked for. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var offset = 0
        while (offset < into.size) {
            val read = read(into, offset, into.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}
