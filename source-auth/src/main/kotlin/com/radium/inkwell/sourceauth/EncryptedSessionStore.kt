package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * root 必须是 noBackupFilesDir 内的独立目录。只在 IO 上处理磁盘和密钥；不存原始来源、密码或普通 JSON。
 * 写入先加密再 fsync / 原子替换，取消登录不会触及旧文件；解密失败保留密文并要求重新登录。
 */
class EncryptedSessionStore(private val root: File, private val cipher: SessionCipher) {
    private val mutex = Mutex()

    suspend fun save(scope: SourceAuthScope, session: CookieSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cookies = session.cookies.filter { it.persistent && it.expiresAt > System.currentTimeMillis() }
            require(cookies.size <= 256 && session.loginData.size <= 2)
            require(cookies.isNotEmpty() || session.loginData.isNotEmpty())
            val origin = scope.originUrl.toHttpUrl()
            require(cookies.all { Cookie.parse(origin, it.toString()) != null })
            val plain = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(2)
                    out.writeInt(cookies.size)
                    cookies.forEach { out.writeUTF(it.toString()) }
                    out.writeInt(session.loginData.size)
                    session.loginData.forEach { (key, value) -> out.writeUTF(key); out.writeUTF(value) }
                }
                bytes.toByteArray()
            }
            require(plain.size <= MAX_BYTES)
            val encrypted = try { cipher.encrypt(plain, binding(scope)) } finally { plain.fill(0) }
            check(root.isDirectory || root.mkdirs())
            val temp = File.createTempFile("auth-", ".tmp", root)
            try {
                FileOutputStream(temp).use { it.write(encrypted); it.fd.sync() }
                Files.move(temp.toPath(), file(scope).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun load(scope: SourceAuthScope): CookieSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(scope)
            if (!file.exists()) return@withLock null
            require(file.length() <= MAX_BYTES + 64)
            val plain = cipher.decrypt(file.readBytes(), binding(scope))
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    val version = input.readInt()
                    require(version in 1..2)
                    val count = input.readInt()
                    require(count in 0..256)
                    val cookies = List(count) {
                        requireNotNull(Cookie.parse(scope.originUrl.toHttpUrl(), input.readUTF()))
                    }
                    val data = if (version == 2) {
                        val size = input.readInt()
                        require(size in 0..2)
                        buildMap { repeat(size) { put(input.readUTF(), input.readUTF()) } }
                    } else emptyMap()
                    require(input.available() == 0)
                    CookieSession(cookies.filter { it.persistent && it.expiresAt > System.currentTimeMillis() }, data)
                }
            } finally { plain.fill(0) }
        }
    }

    suspend fun clear(scope: SourceAuthScope) = withContext(Dispatchers.IO) {
        mutex.withLock { val file = file(scope); check(!file.exists() || file.delete()) }
    }

    private fun binding(scope: SourceAuthScope): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest((scope.sourceId + "\u0000" + scope.originUrl).toByteArray(Charsets.UTF_8))

    private fun file(scope: SourceAuthScope): File = File(root, binding(scope).joinToString("") { "%02x".format(it) } + ".auth")

    private companion object { const val MAX_BYTES = 512 * 1024 }
}
