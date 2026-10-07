package com.strata.app.ai

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exact decimal arithmetic for the assistant: numbers, + - * /, parentheses, unary minus and
 * a postfix % (meaning /100). A small recursive-descent parser; nothing is ever evaluated as code.
 */
object Calculator {
    class CalcError(message: String) : Exception(message)

    private val context = MathContext(34, RoundingMode.HALF_EVEN)
    private const val MAX_LENGTH = 2_000

    fun evaluate(expression: String): BigDecimal {
        if (expression.length > MAX_LENGTH) throw CalcError("Expression is longer than $MAX_LENGTH characters; use sum_transactions for long totals.")
        val parser = Parser(expression.replace('−', '-').replace('×', '*').replace('÷', '/'))
        val value = parser.expression()
        parser.skipSpaces()
        if (!parser.atEnd()) throw CalcError("Unexpected '${parser.peek()}' at position ${parser.pos + 1}")
        return value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun peek() = s[pos]
        fun skipSpaces() { while (!atEnd() && s[pos].isWhitespace()) pos++ }

        fun expression(): BigDecimal {
            var left = term()
            while (true) {
                skipSpaces()
                if (atEnd()) return left
                left = when (peek()) {
                    '+' -> { pos++; left.add(term(), context) }
                    '-' -> { pos++; left.subtract(term(), context) }
                    else -> return left
                }
            }
        }

        fun term(): BigDecimal {
            var left = unary()
            while (true) {
                skipSpaces()
                if (atEnd()) return left
                left = when (peek()) {
                    '*' -> { pos++; left.multiply(unary(), context) }
                    '/' -> {
                        pos++
                        val divisor = unary()
                        if (divisor.signum() == 0) throw CalcError("Division by zero")
                        left.divide(divisor, context)
                    }
                    else -> return left
                }
            }
        }

        fun unary(): BigDecimal {
            skipSpaces()
            if (!atEnd() && peek() == '-') { pos++; return unary().negate() }
            if (!atEnd() && peek() == '+') { pos++; return unary() }
            var value = primary()
            skipSpaces()
            while (!atEnd() && peek() == '%') { pos++; value = value.movePointLeft(2); skipSpaces() }
            return value
        }

        fun primary(): BigDecimal {
            skipSpaces()
            if (atEnd()) throw CalcError("Expression ends too early")
            if (peek() == '(') {
                pos++
                val inner = expression()
                skipSpaces()
                if (atEnd() || peek() != ')') throw CalcError("Missing ')'")
                pos++
                return inner
            }
            val start = pos
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.')) pos++
            val token = s.substring(start, pos)
            if (token.isEmpty()) throw CalcError("Expected a number at position ${start + 1}")
            return token.toBigDecimalOrNull() ?: throw CalcError("'$token' is not a number; use '.' for decimals and no thousands separators")
        }
    }
}
