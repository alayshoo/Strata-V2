package com.strata.app.data.repo

import com.strata.app.data.db.FxDao
import com.strata.app.data.db.FxRateEntity
import com.strata.app.domain.FxTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ECB reference rates via the Frankfurter API. Requests carry only currency codes and dates.
 * Rates are cached locally; we only fetch ranges we don't have yet.
 */
class FxRepository(private val dao: FxDao, private val http: OkHttpClient) {
    val table = dao.observeAll().map { rows ->
        FxTable(rows.groupBy { it.currency }.mapValues { (_, list) -> list.map { it.date to it.perEur } })
    }

    /** Ensures rates for [currencies] cover [from]..today. Returns currencies that could not be fetched. */
    suspend fun ensure(currencies: Set<String>, from: LocalDate, today: LocalDate = LocalDate.now()): Set<String> =
        withContext(Dispatchers.IO) {
            val failed = mutableSetOf<String>()
            for (currency in currencies.map { it.uppercase() }.filter { it != FxTable.BASE_CURRENCY }.toSet()) {
                val first = dao.firstDate(currency)
                val last = dao.lastDate(currency)
                val ranges = buildList {
                    if (first == null || last == null) add(from to today)
                    else {
                        // ECB publishes no rate on weekends, so allow a few days of slack.
                        if (first > from.plusDays(4)) add(from to first.minusDays(1))
                        if (last < today.minusDays(1)) add(last.plusDays(1) to today)
                    }
                }
                for ((start, end) in ranges) {
                    runCatching { fetch(currency, start, end) }
                        .onSuccess { dao.insertAll(it) }
                        .onFailure { failed += currency }
                }
            }
            failed
        }

    private fun fetch(currency: String, start: LocalDate, end: LocalDate): List<FxRateEntity> {
        val url = "https://api.frankfurter.dev/v1/$start..$end?base=EUR&symbols=$currency"
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("Frankfurter ${response.code}")
            val root = Json.parseToJsonElement(response.body.string()).jsonObject
            val rates = root["rates"]?.jsonObject ?: return emptyList()
            return rates.mapNotNull { (date, perCurrency) ->
                val value = perCurrency.jsonObject[currency]?.jsonPrimitive?.content ?: return@mapNotNull null
                FxRateEntity(LocalDate.parse(date), currency, BigDecimal(value))
            }
        }
    }
}
