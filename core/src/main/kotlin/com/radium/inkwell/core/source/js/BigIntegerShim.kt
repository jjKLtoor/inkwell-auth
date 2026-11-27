package com.radium.inkwell.core.source.js

import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import org.mozilla.javascript.Context

/** 来源标识和计数可能超过 JS Number 的精度。只暴露有界整数对象，底层 JDK 实例留在沙箱内。 */
class BigIntegerShim private constructor(private val value: BigInteger) {
    init {
        require(value.bitLength() <= MAX_BITS) { "脚本大整数超过位数限制" }
    }

    fun add(other: BigIntegerShim): BigIntegerShim = calculate { value.add(other.value) }
    fun subtract(other: BigIntegerShim): BigIntegerShim = calculate { value.subtract(other.value) }
    fun multiply(other: BigIntegerShim): BigIntegerShim = calculate { value.multiply(other.value) }
    fun divide(other: BigIntegerShim): BigIntegerShim = calculate { value.divide(other.value) }
    fun mod(other: BigIntegerShim): BigIntegerShim = calculate { value.mod(other.value) }
    fun modPow(exponent: BigIntegerShim, modulus: BigIntegerShim): BigIntegerShim {
        val bits = maxOf(1, modulus.bitLength()).toLong()
        val work = maxOf(1L, bits * bits * maxOf(1, exponent.bitLength()) / 64)
        ScriptNumberBudget.consume(work)
        return calculate { value.modPow(exponent.value, modulus.value) }
    }
    fun compareTo(other: BigIntegerShim): Int = value.compareTo(other.value)
    fun bitLength(): Int = value.bitLength()
    fun signum(): Int = value.signum()
    fun toByteArray(): ByteArray = value.toByteArray()
    fun toString(radix: Int): String = value.toString(radix)
    override fun toString(): String = value.toString()

    companion object {
        private const val MAX_BITS = 4096
        private const val MAX_BYTES = MAX_BITS / 8 + 1 // 补码可能多一个符号字节

        internal fun parse(text: String, radix: Int): BigIntegerShim = calculate {
            require(text.length <= MAX_BITS + 1 && radix in 2..36) { "脚本大整数格式或大小不受支持" }
            BigInteger(text, radix)
        }

        internal fun fromBytes(bytes: ByteArray): BigIntegerShim = calculate {
            require(bytes.size <= MAX_BYTES) { "脚本大整数字节数组超过大小限制" }
            BigInteger(bytes)
        }

        internal fun fromMagnitude(sign: Int, bytes: ByteArray): BigIntegerShim = calculate {
            require(bytes.size <= MAX_BYTES) { "脚本大整数字节数组超过大小限制" }
            BigInteger(sign, bytes)
        }

        internal fun random(bits: Int, random: SecureRandom): BigIntegerShim = calculate {
            require(bits in 0..MAX_BITS) { "脚本大整数超过位数限制" }
            ScriptNumberBudget.consume(maxOf(1L, bits.toLong()))
            BigInteger(bits, random)
        }

        private inline fun calculate(operation: () -> BigInteger): BigIntegerShim {
            if (EngineJsHttp.currentJob()?.isActive == false) throw CancellationException("脚本调用已取消")
            return BigIntegerShim(operation())
        }
    }
}

/** 只向脚本提供有限的随机数和字节操作，Java 随机数实例始终留在包装对象内部。 */
class SecureRandomShim internal constructor() {
    private val random = SecureRandom()

    fun generateSeed(count: Int): ByteArray {
        require(count in 0..4096) { "脚本随机种子超过大小限制" }
        ScriptNumberBudget.consume(maxOf(1L, count.toLong()))
        return random.generateSeed(count)
    }

    fun nextBytes(bytes: ByteArray) {
        require(bytes.size <= 65536) { "脚本随机字节超过大小限制" }
        ScriptNumberBudget.consume(maxOf(1L, bytes.size.toLong()))
        random.nextBytes(bytes)
    }

    fun nextInt(): Int {
        ScriptNumberBudget.consume(1)
        return random.nextInt()
    }

    fun nextInt(bound: Int): Int {
        ScriptNumberBudget.consume(1)
        return random.nextInt(bound)
    }

    internal fun integer(bits: Int): BigIntegerShim = BigIntegerShim.random(bits, random)
}

/** 模幂和随机数据生成不计入 Rhino 指令数，额外按当前 Context 累计，嵌套求值复用额度。 */
private object ScriptNumberBudget {
    private const val MAX_WORK = 64_000_000L
    private val key = Any()

    fun consume(work: Long) {
        if (EngineJsHttp.currentJob()?.isActive == false) throw CancellationException("脚本调用已取消")
        val context = checkNotNull(Context.getCurrentContext())
        val total = (context.getThreadLocal(key) as? Long ?: 0L) + work
        check(total <= MAX_WORK) { "脚本大整数与随机数计算超过预算" }
        context.putThreadLocal(key, total)
    }
}
