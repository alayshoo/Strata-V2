package com.strata.app

import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import com.strata.app.domain.FxTable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.sin
import kotlin.random.Random

/** A plausible two-and-a-half-year ledger for screenshots and tests. */
object SampleData {
    val today: LocalDate = LocalDate.of(2026, 10, 6)

    val sources = listOf(
        SourceEntity(1, "Millennium BCP", SourceType.BANK),
        SourceEntity(2, "Trade Republic", SourceType.BROKER),
        SourceEntity(3, "Revolut", SourceType.BANK),
        SourceEntity(4, "Interactive Brokers", SourceType.BROKER),
        SourceEntity(5, "Self-custody", SourceType.WALLET),
    )

    val assetClasses = listOf(
        AssetClassEntity(1, "Cash", "cobalt", false, 0),
        AssetClassEntity(2, "ETFs", "saffron", false, 1),
        AssetClassEntity(3, "Stocks", "magenta", false, 2),
        AssetClassEntity(4, "Bonds", "sky", false, 3),
        AssetClassEntity(5, "Crypto", "jade", false, 4),
        AssetClassEntity(6, "Credit", "graphite", true, 5),
    )

    val categories = listOf(
        SpendingCategoryEntity(1, "Housing", FlowKind.EXPENSE, "cobalt"),
        SpendingCategoryEntity(2, "Groceries", FlowKind.EXPENSE, "saffron"),
        SpendingCategoryEntity(3, "Dining out", FlowKind.EXPENSE, "magenta"),
        SpendingCategoryEntity(4, "Transport", FlowKind.EXPENSE, "sky"),
        SpendingCategoryEntity(5, "Health", FlowKind.EXPENSE, "jade"),
        SpendingCategoryEntity(6, "Shopping", FlowKind.EXPENSE, "coral"),
        SpendingCategoryEntity(7, "Subscriptions", FlowKind.EXPENSE, "violet"),
        SpendingCategoryEntity(8, "Travel", FlowKind.EXPENSE, "lime"),
        SpendingCategoryEntity(9, "Salary", FlowKind.INCOME, "jade"),
        SpendingCategoryEntity(10, "Other income", FlowKind.INCOME, "saffron"),
    )

    val products = listOf(
        ProductEntity(1, 1, 1, "Current account", "EUR", "PT50 ··· 4821"),
        ProductEntity(2, 1, 1, "Savings deposit", "EUR", "··· 9930"),
        ProductEntity(3, 2, 2, "VWCE", "EUR", "IE00BK5BQT80"),
        ProductEntity(4, 2, 1, "Cash balance", "EUR"),
        ProductEntity(5, 2, 4, "EU Govt Bond 0-1y", "EUR", "LU2233156582"),
        ProductEntity(6, 4, 3, "Apple", "USD", "AAPL"),
        ProductEntity(7, 4, 3, "Microsoft", "USD", "MSFT"),
        ProductEntity(8, 3, 1, "EUR wallet", "EUR"),
        ProductEntity(9, 5, 5, "Bitcoin", "EUR", "BTC"),
        ProductEntity(10, 1, 6, "Visa Gold", "EUR", "··· 3302"),
    )

    val fx = FxTable(
        mapOf(
            "USD" to generateSequence(LocalDate.of(2024, 1, 1)) { it.plusDays(7) }.takeWhile { it <= today }
                .mapIndexed { i, d -> d to BigDecimal(1.08 + 0.04 * sin(i / 9.0)).setScale(4, RoundingMode.HALF_UP) }.toList()
        )
    )

    val snapshots: List<SnapshotEntity> = buildList {
        var id = 1L
        val start = LocalDate.of(2024, 4, 30)
        val months = generateSequence(start) { it.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth()) }
            .takeWhile { it <= today }.toList() + today
        val rnd = Random(7)
        months.forEachIndexed { i, d ->
            val t = i.toDouble()
            fun add(product: Long, value: Double, qty: Double? = null, price: Double? = null) {
                add(
                    SnapshotEntity(
                        id++, product, d, BigDecimal(value).setScale(2, RoundingMode.HALF_UP),
                        qty?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
                        price?.let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) },
                        importId = 1,
                    )
                )
            }
            add(1, 4200 + 900 * sin(t / 2.0) + rnd.nextDouble(-300.0, 300.0))
            add(2, 15000 + 250 * t)
            val vwceUnits = 160 + 9.5 * t
            val vwcePrice = 112 + 1.1 * t + 6 * sin(t / 3.0)
            add(3, vwceUnits * vwcePrice, vwceUnits, vwcePrice)
            add(4, 600 + 300 * sin(t))
            if (i >= 8) add(5, 6000 + 40 * (t - 8))
            add(6, (40 + 0.0 * t) * (190 + 3 * t + 12 * sin(t / 2.5)), 40.0, 190 + 3 * t + 12 * sin(t / 2.5))
            add(7, 18.0 * (410 + 2.2 * t + 15 * sin(t / 3.2)), 18.0, 410 + 2.2 * t + 15 * sin(t / 3.2))
            add(8, 900 + 400 * sin(t / 1.5))
            add(9, 0.085 * (58000 + 2600 * t + 9000 * sin(t / 2.2)), 0.085, 58000 + 2600 * t + 9000 * sin(t / 2.2))
            add(10, 650 + 300 * sin(t / 1.3) + rnd.nextDouble(0.0, 200.0))
        }
    }

    val transactions: List<TransactionEntity> = buildList {
        var id = 1L
        val rnd = Random(11)
        var month = today.withDayOfMonth(1).minusMonths(9)
        while (month <= today) {
            fun tx(day: Int, amount: Double, desc: String, who: String, kind: TxKind, cat: Long?, product: Long = 1, group: String? = null) {
                val d = month.withDayOfMonth(minOf(day, month.lengthOfMonth()))
                if (d > today) return
                add(TransactionEntity(id++, product, d, BigDecimal(amount).setScale(2, RoundingMode.HALF_UP), desc, who, kind, cat, group, importId = 1))
            }
            tx(1, -1150.0, "Rent October", "Imobiliária Luz", TxKind.EXPENSE, 1)
            tx(24, 3480.0 + rnd.nextInt(0, 3) * 120, "Salary", "Acme Labs Lda", TxKind.INCOME, 9)
            repeat(5) { tx(3 + it * 6, -(48 + rnd.nextDouble(0.0, 70.0)), "Groceries", listOf("Pingo Doce", "Continente", "Lidl")[it % 3], TxKind.EXPENSE, 2) }
            repeat(3) { tx(5 + it * 8, -(18 + rnd.nextDouble(0.0, 45.0)), "Restaurant", listOf("Taberna da Rua", "Sushi Kyo", "O Velho Eurico")[it], TxKind.EXPENSE, 3, product = 8) }
            tx(9, -40.0, "Navegante pass", "Carris Metropolitana", TxKind.EXPENSE, 4)
            tx(14, -(30 + rnd.nextDouble(0.0, 90.0)), "Pharmacy", "Farmácia Estácio", TxKind.EXPENSE, 5)
            tx(16, -(40 + rnd.nextDouble(0.0, 220.0)), "Online order", "Fnac", TxKind.EXPENSE, 6, product = 10)
            tx(2, -15.99, "Spotify Premium", "Spotify", TxKind.EXPENSE, 7, product = 10)
            tx(2, -11.99, "iCloud+ 2TB", "Apple", TxKind.EXPENSE, 7, product = 10)
            if (month.monthValue in listOf(7, 8, 12, 4)) tx(19, -(380 + rnd.nextDouble(0.0, 500.0)), "Flights", "TAP Air Portugal", TxKind.EXPENSE, 8, product = 10)
            val g = "t-${month}"
            tx(25, -800.0, "Monthly investing", "Trade Republic", TxKind.TRANSFER, null, product = 1, group = g)
            tx(25, 800.0, "Deposit", "Millennium BCP", TxKind.TRANSFER, null, product = 4, group = g)
            tx(26, -760.0, "Savings plan VWCE", "Trade Republic", TxKind.TRADE, null, product = 4, group = "$g-trade")
            month = month.plusMonths(1)
        }
    }.sortedByDescending { it.date }

    val imports = listOf(
        ImportEntity(3, 2, "extrato_setembro_2026.pdf", "1 balance, 32 transactions", today.minusDays(4).toEpochDay() * 86_400_000),
        ImportEntity(2, 1, "TR_account_statement_Q3.pdf", "4 balances, 9 transactions, 1 new product", today.minusDays(12).toEpochDay() * 86_400_000),
        ImportEntity(1, 1, "ibkr_activity_2026.csv", "2 balances", today.minusDays(30).toEpochDay() * 86_400_000, undoneAt = today.minusDays(29).toEpochDay() * 86_400_000),
    )
}
