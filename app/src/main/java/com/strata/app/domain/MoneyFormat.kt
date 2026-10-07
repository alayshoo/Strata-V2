package com.strata.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

object MoneyFormat {
    private fun currencyFormat(currency: String, decimals: Int): NumberFormat =
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            runCatching { this.currency = Currency.getInstance(currency) }
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }

    fun full(amount: BigDecimal, currency: String = FxTable.BASE_CURRENCY): String =
        currencyFormat(currency, 2).format(amount.setScale(2, RoundingMode.HALF_UP))

    fun whole(amount: BigDecimal, currency: String = FxTable.BASE_CURRENCY): String =
        currencyFormat(currency, 0).format(amount.setScale(0, RoundingMode.HALF_UP))

    fun signedWhole(amount: BigDecimal, currency: String = FxTable.BASE_CURRENCY): String =
        signed(amount, currency, rounded = true)

    fun signed(amount: BigDecimal, currency: String = FxTable.BASE_CURRENCY, rounded: Boolean = false): String {
        val body = if (rounded) whole(amount.abs(), currency) else full(amount.abs(), currency)
        return when {
            amount.signum() > 0 -> "+$body"
            amount.signum() < 0 -> "−$body"
            else -> body
        }
    }

    /** Short axis labels: 950, 12k, 1.2M. */
    fun compact(value: Double): String {
        val abs = kotlin.math.abs(value)
        val sign = if (value < 0) "−" else ""
        return when {
            abs >= 1_000_000 -> sign + trim(abs / 1_000_000) + "M"
            abs >= 1_000 -> sign + trim(abs / 1_000) + "k"
            else -> sign + abs.toLong().toString()
        }
    }

    private fun trim(v: Double): String =
        if (v >= 100 || v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.getDefault(), "%.1f", v)

    /** Model usage in US dollars: cents normally, a tenth of a cent for single cheap requests. */
    fun usage(usd: Double): String = when {
        usd < 0.001 -> "<$0.001"
        usd < 0.01 -> String.format(Locale.US, "$%.3f", usd)
        else -> String.format(Locale.US, "$%.2f", usd)
    }

    fun percent(ratio: Double): String = String.format(Locale.getDefault(), "%.1f%%", ratio * 100)

    fun quantity(q: BigDecimal): String = q.stripTrailingZeros().toPlainString()

    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun date(date: LocalDate): String = date.format(dateFormat)
}
