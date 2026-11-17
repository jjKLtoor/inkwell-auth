package com.radium.inkwell.core.source.js

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 只允许内存流，既不暴露真实 JDK 流，也不能从路径、文件或任意对象创建流。 */
internal const val SCRIPT_BUFFER_LIMIT = 4 * 1024 * 1024

internal fun checkMemoryOperation() {
    if (EngineJsHttp.currentJob()?.isActive == false) {
        throw kotlinx.coroutines.CancellationException("脚本所在的引擎调用已取消")
    }
}

class MemoryOutputStreamShim(initialSize: Int = 32) {
    private val buffer = ByteArrayOutputStream(initialSize.also { require(it in 0..SCRIPT_BUFFER_LIMIT) })
    fun write(value: Int) { reserve(1); buffer.write(value) }
    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length); buffer.write(bytes, offset, length)
    }
    private fun reserve(length: Int) {
        checkMemoryOperation()
        require(length <= SCRIPT_BUFFER_LIMIT - buffer.size()) { "脚本内存流超过大小限制" }
    }
    fun size(): Int = buffer.size()
    fun reset() = buffer.reset()
    fun flush() = Unit
    fun close() = Unit // ByteArrayOutputStream 关闭后仍允许取出内容，与 JDK 一致。
    fun toByteArray(): ByteArray = buffer.toByteArray()
    override fun toString(): String = buffer.toString(Charsets.UTF_8)
    fun toString(charset: String): String = buffer.toString(java.nio.charset.Charset.forName(charset))
}

class GzipOutputStreamShim(target: MemoryOutputStreamShim) {
    private var inputBytes = 0
    private val gzip = GZIPOutputStream(object : OutputStream() {
        override fun write(value: Int) = target.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = target.write(bytes, offset, length)
    })
    fun write(value: Int) { reserve(1); gzip.write(value) }
    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length); gzip.write(bytes, offset, length)
    }
    private fun reserve(length: Int) {
        checkMemoryOperation()
        require(length <= SCRIPT_BUFFER_LIMIT - inputBytes) { "脚本压缩输入超过大小限制" }
        inputBytes += length
    }
    fun flush() = gzip.flush()
    fun finish() = gzip.finish()
    fun close() = gzip.close()
}

class MemoryInputStreamShim(bytes: ByteArray) {
    private val input = ByteArrayInputStream(bytes.also { require(it.size <= SCRIPT_BUFFER_LIMIT) }.copyOf())
    fun read(): Int { checkMemoryOperation(); return input.read() }
    fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
    fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        checkMemoryOperation(); return input.read(bytes, offset, length)
    }
    fun available(): Int = input.available()
    fun close() = input.close()
    fun readAllBytes(): ByteArray { checkMemoryOperation(); return input.readAllBytes() }
}

/** 解压前限制压缩包大小，解压过程中逐块限额，避免小压缩包造成无界分配。 */
internal fun gunzip(input: MemoryInputStreamShim): MemoryInputStreamShim {
    val output = MemoryOutputStreamShim()
    GZIPInputStream(ByteArrayInputStream(input.readAllBytes())).use { gzip ->
        val chunk = ByteArray(8192)
        while (true) {
            checkMemoryOperation()
            val count = gzip.read(chunk)
            if (count < 0) break
            output.write(chunk, 0, count)
        }
    }
    return MemoryInputStreamShim(output.toByteArray())
}

class MemoryReaderShim(input: MemoryInputStreamShim, charset: String) {
    private val reader = String(input.readAllBytes(), java.nio.charset.Charset.forName(charset)).reader().buffered()
    fun readLine(): String? { checkMemoryOperation(); return reader.readLine() }
    fun close() = reader.close()
}
