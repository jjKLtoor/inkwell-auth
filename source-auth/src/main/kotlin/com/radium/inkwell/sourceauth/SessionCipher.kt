package com.radium.inkwell.sourceauth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionCipher {
    fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray
}

/** 同一信封用于设备实现与 JVM 真 AES/GCM 测试；AAD 绑定来源和 origin，文件互换也不能解密。 */
open class AesGcmSessionCipher(private val keyProvider: () -> SecretKey) : SessionCipher {
    override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        require(cipher.iv.size == 12)
        cipher.updateAAD(binding)
        return ByteBuffer.allocate(1 + 12 + plain.size + 16).put(1.toByte()).put(cipher.iv)
            .put(cipher.doFinal(plain)).array()
    }

    override fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray {
        require(encrypted.size >= 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(binding)
        return cipher.doFinal(encrypted, 13, encrypted.size - 13)
    }
}

/** 独立 alias，不复用或改动 WebDAV 密钥；无明文回退，也不自动重建被损坏密文。 */
class KeystoreSessionCipher : AesGcmSessionCipher({ SourceAuthKey.get() })

private object SourceAuthKey {
    private var cached: SecretKey? = null

    @Synchronized
    fun get(): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (store.getEntry("inkwell_source_auth_v1", null) as? KeyStore.SecretKeyEntry)?.secretKey
        return (existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("inkwell_source_auth_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()).also { cached = it }
    }
}
