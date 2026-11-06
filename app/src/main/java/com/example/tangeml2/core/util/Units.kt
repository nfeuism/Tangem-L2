package com.example.tangeml2.core.util

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Thrown when the user's amount string cannot be represented exactly. */
class AmountFormatException(message: String) : IllegalArgumentException(message)

/**
 * Exact decimal <-> integer conversions for chain amounts.
 *
 * Everything is BigDecimal/BigInteger: rounding a wei value with Double silently
 * changes how much money moves.
 */
object Units {

    private val DECIMAL_SHAPE = Regex("^[0-9]*\\.?[0-9]*$")

    /** `1.5` ETH (18 decimals) -> 1500000000000000000. */
    fun parseAmountToRaw(input: String, decimals: Int): BigInteger {
        var s = input.trim().replace("_", "")
        if (s.isEmpty()) throw AmountFormatException("请输入金额")
        if (s.startsWith("-")) throw AmountFormatException("金额不能为负数")
        if (s.startsWith("+")) s = s.substring(1)
        if (s.startsWith(".")) s = "0$s"
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.isEmpty() || !DECIMAL_SHAPE.matches(s) || s == ".") {
            throw AmountFormatException("金额格式不正确：只允许数字和一个小数点")
        }
        val value = try {
            BigDecimal(s)
        } catch (e: NumberFormatException) {
            throw AmountFormatException("金额格式不正确：$input")
        }
        if (value.scale() > decimals) {
            val trimmed = value.stripTrailingZeros()
            if (trimmed.scale() > decimals) {
                throw AmountFormatException("小数位超过精度上限（最多 $decimals 位）")
            }
        }
        return try {
            value.movePointRight(decimals).toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw AmountFormatException("金额无法精确表示为最小单位")
        }
    }

    /** 1500000000000000000 -> `1.5` (trailing zeros trimmed, never rounded). */
    fun formatExact(raw: BigInteger, decimals: Int): String {
        if (raw.signum() == 0) return "0"
        return BigDecimal(raw, decimals).stripTrailingZeros().toPlainString()
    }

    /** Human display with at most [maxFraction] decimals; only for non-signing labels. */
    fun formatDisplay(raw: BigInteger, decimals: Int, maxFraction: Int = 6): String {
        if (raw.signum() == 0) return "0"
        val scaled = BigDecimal(raw, decimals)
        val limited = if (scaled.scale() > maxFraction) {
            scaled.setScale(maxFraction, RoundingMode.HALF_UP)
        } else {
            scaled
        }
        return limited.stripTrailingZeros().toPlainString()
    }

    /** Wei -> gwei string, e.g. `0.125`. */
    fun formatGwei(wei: BigInteger): String {
        if (wei.signum() == 0) return "0"
        return BigDecimal(wei, 9).stripTrailingZeros().toPlainString()
    }

    /** 10^decimals as a BigInteger. */
    fun pow10(decimals: Int): BigInteger = BigInteger.TEN.pow(decimals)
}
