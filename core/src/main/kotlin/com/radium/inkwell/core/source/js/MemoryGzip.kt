package com.radium.inkwell.core.source.js

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException

internal const val MAX_SCRIPT_BUFFER = 4 * 1024 * 1024

private fun checkBufferSize(size: Int, remaining: Int) {
    if (EngineJsHttp.currentJob()?.isActive == false) throw CancellationException("脚本调用已取消")
    require(size in 0..remaining) { "脚本内存压缩超过大小限制" }
}

/** 脚本只能使用有界内存缓冲区，实际 JDK 流不交给沙箱。 */
class MemoryOutputShim internal constructor(initialSize: Int) {
    private val buffer = ByteArrayOutputStream(initialSize.also { checkBufferSize(it, MAX_SCRIPT_BUFFER) })

    internal fun append(bytes: ByteArray, offset: Int, length: Int) {
        checkBufferSize(length, MAX_SCRIPT_BUFFER - buffer.size())
        buffer.write(bytes, offset, length)
    }

    internal fun appendByte(byte: Int) {
        checkBufferSize(1, MAX_SCRIPT_BUFFER - buffer.size())
        buffer.write(byte)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    fun write(bytes: ByteArray, offset: Int, length: Int) = append(bytes, offset, length)

    fun flush() = Unit

    fun close() = Unit
}

class GzipOutputShim internal constructor(target: MemoryOutputShim) {
    private var written = 0
    private val stream = GZIPOutputStream(object : OutputStream() {
        override fun write(b: Int) = target.appendByte(b)
        override fun write(b: ByteArray, off: Int, len: Int) = target.append(b, off, len)
    })

    fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        checkBufferSize(length, MAX_SCRIPT_BUFFER - written)
        stream.write(bytes, offset, length)
        written += length
    }

    fun finish() = stream.finish()
    fun close() = stream.close()
}

class MemoryInputShim internal constructor(private val bytes: ByteArray) {
    init { checkBufferSize(bytes.size, MAX_SCRIPT_BUFFER) }

    internal fun gunzip(): MemoryInputShim {
        val result = MemoryOutputShim(32)
        GZIPInputStream(bytes.inputStream()).use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                result.append(chunk, 0, count)
            }
        }
        return MemoryInputShim(result.toByteArray())
    }

    internal fun reader(charset: String): MemoryLineReader = MemoryLineReader(String(bytes, charset(charset)))
}

class MemoryLineReader internal constructor(text: String) {
    private val reader = text.reader().buffered()

    fun readLine(): String? {
        checkBufferSize(0, MAX_SCRIPT_BUFFER)
        return reader.readLine()
    }

    fun close() = reader.close()
}
