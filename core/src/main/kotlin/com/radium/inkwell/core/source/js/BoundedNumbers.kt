package com.radium.inkwell.core.source.js

import java.math.BigInteger
import java.security.SecureRandom

/** 原生大整数计算也计预算；只在最外层脚本调用重置，嵌套求值不能刷新额度。 */
internal object ScriptComputationBudget {
    private val used = ThreadLocal<Long>()
    fun reset() { used.set(0L) }
    fun consume(work: Long) {
        checkMemoryOperation()
        val next = (used.get() ?: 0L) + work
        check(next <= 64_000_000L) { "脚本大整数计算超过预算" }
        used.set(next)
    }
}

class SecureRandomShim {
    private val random = SecureRandom()
    fun generateSeed(count: Int): ByteArray {
        checkMemoryOperation(); require(count in 0..4096) { "脚本随机种子超过大小限制" }
        return random.generateSeed(count)
    }
    fun nextBytes(bytes: ByteArray) {
        checkMemoryOperation(); require(bytes.size <= 65536) { "脚本随机字节超过大小限制" }
        random.nextBytes(bytes)
    }
    fun nextInt(): Int { checkMemoryOperation(); return random.nextInt() }
    fun nextInt(bound: Int): Int { checkMemoryOperation(); return random.nextInt(bound) }
    // 只返回受限大整数替身，不返回 JDK 的 Random 对象。
    fun randomInteger(bits: Int): BigIntegerShim {
        require(bits in 0..4096) { "脚本大整数超过大小限制" }
        checkMemoryOperation()
        return BigIntegerShim(BigInteger(bits, random))
    }
}

/** 真正 BigInteger 始终为私有字段；每个运算都限制位数，模幂另按规模计计算预算。 */
class BigIntegerShim internal constructor(private val value: BigInteger) {
    init { require(value.bitLength() <= 4096) { "脚本大整数超过大小限制" } }
    fun subtract(other: BigIntegerShim): BigIntegerShim { checkMemoryOperation(); return BigIntegerShim(value.subtract(other.value)) }
    fun mod(other: BigIntegerShim): BigIntegerShim { checkMemoryOperation(); return BigIntegerShim(value.mod(other.value)) }
    fun modPow(exponent: BigIntegerShim, modulus: BigIntegerShim): BigIntegerShim {
        val bits = maxOf(1, modulus.value.bitLength()).toLong()
        ScriptComputationBudget.consume(maxOf(1L, bits * bits * maxOf(1, exponent.value.bitLength()) / 64))
        return BigIntegerShim(value.modPow(exponent.value, modulus.value)).also { checkMemoryOperation() }
    }
    fun bitLength(): Int = value.bitLength()
    fun signum(): Int = value.signum()
    fun compareTo(other: BigIntegerShim): Int = value.compareTo(other.value)
    fun toByteArray(): ByteArray = value.toByteArray()
    override fun toString(): String = value.toString()
    fun toString(radix: Int): String = value.toString(radix)
}

internal fun parseBigInteger(text: String, radix: Int): BigIntegerShim {
    require(text.length <= 4097 && radix in 2..36) { "脚本大整数格式或大小不受支持" }
    checkMemoryOperation()
    return BigIntegerShim(BigInteger(text, radix))
}
