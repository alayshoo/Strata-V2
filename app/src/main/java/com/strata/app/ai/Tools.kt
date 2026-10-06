package com.strata.app.ai

import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.db.TxKind
import com.strata.app.domain.FlowItem
import com.strata.app.domain.FxTable
import com.strata.app.domain.ValueFlow
import com.strata.app.domain.ValuePoint
import com.strata.app.domain.ValuedProduct
import com.strata.app.domain.Valuator
import com.strata.app.domain.netWorth
import com.strata.app.domain.spendingByCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** JSON-schema tool definitions sent to the model. */
object ToolSpecs {
    private fun tool(name: String, description: String, properties: JsonObject = JsonObject(emptyMap()), required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    put("properties", properties)
                    putJsonArray("required") { required.forEach { add(it) } }
                }
            }
        }

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun props(vararg entries: Pair<String, JsonElement>) = JsonObject(entries.toMap())

    private fun arraySchema(item: JsonObject, required: List<String>, description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") {
            put("type", "object")
            put("properties", item)
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    private val productPointer: Array<Pair<String, JsonElement>> = arrayOf(
        "product_id" to prop("integer", "Id of an existing product."),
        "new_product_ref" to prop("string", "Ref of a product staged with stage_product in this turn."),
    )

    private val kinds = TxKind.entries.joinToString(", ") { it.name.lowercase() }

    val all: JsonArray = buildJsonArray {
        add(tool("list_sources", "List the financial institutions (sources) the user has set up."))
        add(tool("list_asset_classes", "List asset classes. Liability classes hold amounts owed."))
        add(tool("list_spending_categories", "List spending categories with their kind (expense or income)."))
        add(
            tool(
                "list_products", "List products (accounts, holdings, cards) with their latest recorded balance.",
                props("source_id" to prop("integer", "Only products of this source.")),
            )
        )
        add(
            tool(
                "get_snapshots", "Balances recorded for one product.",
                props(
                    "product_id" to prop("integer", "Product id."),
                    "from" to prop("string", "Start date, YYYY-MM-DD."),
                    "to" to prop("string", "End date, YYYY-MM-DD."),
                ),
                listOf("product_id"),
            )
        )
        add(
            tool(
                "get_transactions", "Recorded transactions, newest first. Use to avoid duplicates before staging.",
                props(
                    "product_id" to prop("integer", "Only this product."),
                    "from" to prop("string", "Start date, YYYY-MM-DD."),
                    "to" to prop("string", "End date, YYYY-MM-DD."),
                    "kind" to prop("string", "One of: $kinds."),
                    "limit" to prop("integer", "Max rows, up to 300. Default 100."),
                ),
            )
        )
        add(
            tool(
                "find_transfer_candidates",
                "Find recorded transfer or trade legs with no counterpart yet, near a date. Use to link the other side of a transfer.",
                props(
                    "date" to prop("string", "Date of the leg you are staging, YYYY-MM-DD."),
                    "window_days" to prop("integer", "Days either side to search. Default 5."),
                ),
                listOf("date"),
            )
        )
        add(
            tool(
                "get_portfolio", "Value of every product and asset class in EUR at a date, plus net worth.",
                props("date" to prop("string", "YYYY-MM-DD, default today.")),
            )
        )
        add(
            tool(
                "get_spending_summary", "Income and spending by category in EUR for a period.",
                props(
                    "from" to prop("string", "Start date, YYYY-MM-DD."),
                    "to" to prop("string", "End date, YYYY-MM-DD."),
                ),
                listOf("from", "to"),
            )
        )
        add(
            tool(
                "stage_product",
                "Stage a new product under an existing source and asset class. Only when no existing product matches.",
                props(
                    "ref" to prop("string", "Short unique ref you choose, used as new_product_ref later."),
                    "source_id" to prop("integer", "Existing source id."),
                    "asset_class_id" to prop("integer", "Existing asset class id."),
                    "name" to prop("string", "Human name, e.g. 'Current account' or 'VWCE'."),
                    "currency" to prop("string", "ISO 4217 code the statement reports this product in."),
                    "identifier" to prop("string", "IBAN last 4, ISIN or ticker, if shown."),
                ),
                listOf("ref", "source_id", "asset_class_id", "name", "currency"),
            )
        )
        add(
            tool(
                "stage_snapshots",
                "Stage balances (closing value of a product on a date) in the product's currency. Liabilities: amount owed as a positive number.",
                props(
                    "items" to arraySchema(
                        props(
                            *productPointer,
                            "date" to prop("string", "YYYY-MM-DD."),
                            "value" to prop("string", "Decimal value, e.g. '1520.37'."),
                            "quantity" to prop("string", "Units held, for securities."),
                            "unit_price" to prop("string", "Price per unit, for securities."),
                            "note" to prop("string", "Optional note."),
                        ),
                        listOf("date", "value"),
                        "Balances to stage.",
                    )
                ),
                listOf("items"),
            )
        )
        add(
            tool(
                "stage_transactions",
                "Stage transactions. Amounts are signed from the product's point of view (money out is negative).",
                props(
                    "items" to arraySchema(
                        props(
                            *productPointer,
                            "date" to prop("string", "YYYY-MM-DD."),
                            "amount" to prop("string", "Signed decimal in the product's currency."),
                            "description" to prop("string", "Statement description, cleaned up."),
                            "counterparty" to prop("string", "Merchant, employer or other party."),
                            "kind" to prop("string", "One of: $kinds."),
                            "spending_category_id" to prop("integer", "Only for expense and income."),
                            "transfer_key" to prop("string", "Same key on both legs staged now to link them."),
                            "link_to_transaction_id" to prop("integer", "Existing transaction id that is the other leg."),
                            "quantity" to prop("string", "Units bought (+) or sold (−) on a trade's security leg."),
                            "fx_rate" to prop("string", "Rate the institution applied, units of this currency per 1 EUR."),
                            "allow_duplicate" to prop("boolean", "Stage even if an identical transaction exists."),
                        ),
                        listOf("date", "amount", "description", "kind"),
                        "Transactions to stage.",
                    )
                ),
                listOf("items"),
            )
        )
        add(
            tool(
                "link_transfer", "Link two or more existing transactions as legs of one transfer or trade.",
                props(
                    "transaction_ids" to buildJsonObject {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                ),
                listOf("transaction_ids"),
            )
        )
        add(
            tool(
                "sum_transactions",
                "Exact totals of a product's transactions, recorded and staged: count, sum of amounts, sum of quantities. " +
                    "Use this instead of adding numbers yourself, e.g. to derive a closing balance from a full-history export.",
                props(
                    *productPointer,
                    "from" to prop("string", "Start date, YYYY-MM-DD, inclusive."),
                    "to" to prop("string", "End date, YYYY-MM-DD, inclusive."),
                ),
            )
        )
        add(
            tool(
                "calculate",
                "Exact decimal arithmetic for small one-off sums, e.g. units times price or a percentage. " +
                    "Supports + - * / ( ) and %. For totals over many rows use sum_transactions instead of typing them in.",
                props("expression" to prop("string", "For example \"881.23 * 1.1042\" or \"(7240.39 + 973.03) * 2%\".")),
                listOf("expression"),
            )
        )
        add(tool("get_staged_changes", "Show everything staged so far in this turn, plus balance checks against the transactions."))
        add(tool("clear_staged_changes", "Discard everything staged in this turn to start over."))
    }
}

/**
 * Executes tool calls. Reads go to the database; writes are only staged into [staged].
 * Every argument is validated so the model gets a precise error to correct.
 */
class ToolExecutor(
    private val db: StrataDatabase,
    private val fxTable: suspend () -> FxTable,
    private val today: LocalDate = LocalDate.now(),
) {
    var staged = ChangeSet()
        private set

    fun reset(initial: ChangeSet = ChangeSet()) { staged = initial }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(name: String, arguments: String): String = try {
        val args = if (arguments.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(arguments).jsonObject
        run(name, args).toString()
    } catch (e: ToolError) {
        buildJsonObject { put("error", e.message) }.toString()
    } catch (e: Exception) {
        buildJsonObject { put("error", "${e::class.simpleName}: ${e.message}") }.toString()
    }

    /** Short human line shown in the chat for each call. */
    fun describe(name: String): String = when (name) {
        "list_sources" -> "Checked your sources"
        "list_asset_classes" -> "Checked your asset classes"
        "list_spending_categories" -> "Checked your spending categories"
        "list_products" -> "Looked up products"
        "get_snapshots" -> "Read recorded balances"
        "get_transactions" -> "Read recorded transactions"
        "find_transfer_candidates" -> "Searched for matching transfers"
        "get_portfolio" -> "Valued the portfolio"
        "get_spending_summary" -> "Summarised spending"
        "stage_product" -> "Staged a new product"
        "stage_snapshots" -> "Staged balances"
        "stage_transactions" -> "Staged transactions"
        "link_transfer" -> "Staged a transfer link"
        "sum_transactions" -> "Added up transactions"
        "calculate" -> "Calculated"
        "get_staged_changes" -> "Reviewed staged changes"
        "clear_staged_changes" -> "Cleared staged changes"
        else -> name
    }

    private class ToolError(message: String) : Exception(message)

    private suspend fun run(name: String, args: JsonObject): JsonElement = when (name) {
        "list_sources" -> buildJsonArray {
            db.setupDao().sources().forEach { s ->
                addJsonObject { put("id", s.id); put("name", s.name); put("type", s.type.name.lowercase()); if (s.notes.isNotBlank()) put("notes", s.notes) }
            }
        }
        "list_asset_classes" -> buildJsonArray {
            db.setupDao().assetClasses().forEach { c ->
                addJsonObject { put("id", c.id); put("name", c.name); put("is_liability", c.isLiability) }
            }
        }
        "list_spending_categories" -> buildJsonArray {
            db.setupDao().spendingCategories().forEach { c ->
                addJsonObject { put("id", c.id); put("name", c.name); put("kind", c.kind.name.lowercase()) }
            }
        }
        "list_products" -> listProducts(args.long("source_id"))
        "get_snapshots" -> {
            val productId = args.long("product_id") ?: throw ToolError("product_id is required")
            requireProduct(productId)
            buildJsonArray {
                db.ledgerDao().snapshotsFor(productId, args.date("from") ?: LocalDate.MIN, args.date("to") ?: LocalDate.MAX).forEach { s ->
                    addJsonObject {
                        put("id", s.id); put("date", s.date.toString()); put("value", s.value.toPlainString())
                        s.quantity?.let { put("quantity", it.toPlainString()) }
                        s.unitPrice?.let { put("unit_price", it.toPlainString()) }
                    }
                }
            }
        }
        "get_transactions" -> {
            val kind = args.string("kind")?.let(::parseKind)
            val limit = (args.long("limit") ?: 100).toInt().coerceIn(1, 300)
            buildJsonArray {
                db.ledgerDao().transactions(
                    args.long("product_id"), args.date("from") ?: LocalDate.of(1900, 1, 1),
                    args.date("to") ?: LocalDate.of(2999, 1, 1), kind, limit,
                ).forEach { t -> add(transactionJson(t)) }
            }
        }
        "find_transfer_candidates" -> {
            val date = args.date("date") ?: throw ToolError("date is required")
            val window = (args.long("window_days") ?: 5).coerceIn(0, 31)
            buildJsonArray {
                db.ledgerDao().unlinkedTransfers(date.minusDays(window), date.plusDays(window)).forEach { add(transactionJson(it)) }
            }
        }
        "get_portfolio" -> portfolio(args.date("date") ?: today)
        "get_spending_summary" -> spendingSummary(
            args.date("from") ?: throw ToolError("from is required"),
            args.date("to") ?: throw ToolError("to is required"),
        )
        "stage_product" -> stageProduct(args)
        "stage_snapshots" -> stageSnapshots(args)
        "stage_transactions" -> stageTransactions(args)
        "link_transfer" -> {
            val ids = args["transaction_ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
            if (ids.size < 2) throw ToolError("Provide at least two transaction ids")
            ids.forEach { if (db.ledgerDao().transaction(it) == null) throw ToolError("Transaction $it does not exist") }
            staged = staged.copy(links = staged.links + TransferLink(ids))
            buildJsonObject { put("staged", "link of ${ids.joinToString()}") }
        }
        "sum_transactions" -> sumTransactions(args)
        "calculate" -> {
            val expression = args.string("expression") ?: throw ToolError("expression is required")
            val result = try { Calculator.evaluate(expression) } catch (e: Calculator.CalcError) { throw ToolError(e.message ?: "Invalid expression") }
            buildJsonObject { put("expression", expression); put("result", result.toPlainString()) }
        }
        "get_staged_changes" -> buildJsonObject {
            put("staged", json.parseToJsonElement(json.encodeToString(ChangeSet.serializer(), staged)))
            checks()?.let { put("checks", it) }
        }
        "clear_staged_changes" -> {
            staged = ChangeSet(fileNames = staged.fileNames)
            buildJsonObject { put("cleared", true) }
        }
        else -> throw ToolError("Unknown tool $name")
    }

    private suspend fun listProducts(sourceId: Long?): JsonArray {
        val dao = db.ledgerDao()
        return buildJsonArray {
            dao.products().filter { sourceId == null || it.sourceId == sourceId }.forEach { p ->
                val latest = dao.snapshotsFor(p.id, LocalDate.MIN, LocalDate.MAX).lastOrNull()
                addJsonObject {
                    put("id", p.id); put("name", p.name); put("source_id", p.sourceId)
                    put("asset_class_id", p.assetClassId); put("currency", p.currency)
                    if (p.identifier.isNotBlank()) put("identifier", p.identifier)
                    if (p.archived) put("archived", true)
                    latest?.let { putJsonObject("latest_balance") { put("date", it.date.toString()); put("value", it.value.toPlainString()) } }
                }
            }
            staged.products.forEach { p ->
                addJsonObject { put("new_product_ref", p.ref); put("name", p.name); put("source_id", p.sourceId); put("currency", p.currency); put("staged", true) }
            }
        }
    }

    private suspend fun portfolio(date: LocalDate): JsonObject {
        val products = db.ledgerDao().products()
        val classes = db.setupDao().assetClasses()
        val snapshots = db.backupDao().snapshots()
        val valuator = Valuator(
            products.map { ValuedProduct(it.id, it.assetClassId, it.currency) },
            snapshots.map { ValuePoint(it.productId, it.date, it.value, it.quantity, it.unitPrice) },
            fxTable(),
            db.backupDao().transactions().map { ValueFlow(it.productId, it.date, it.amount, it.quantity) },
        )
        val byClass = valuator.byAssetClass(date)
        val liabilities = classes.filter { it.isLiability }.map { it.id }.toSet()
        return buildJsonObject {
            put("date", date.toString())
            put("net_worth_eur", netWorth(byClass, liabilities).money())
            putJsonArray("asset_classes") {
                classes.forEach { c ->
                    byClass[c.id]?.let { v -> addJsonObject { put("id", c.id); put("name", c.name); put("value_eur", v.money()); put("is_liability", c.isLiability) } }
                }
            }
            putJsonArray("products") {
                products.forEach { p ->
                    val value = valuator.valueAt(p.id, date) ?: return@forEach
                    val balance = valuator.latestOnOrBefore(p.id, date)
                    addJsonObject {
                        put("id", p.id); put("name", p.name); put("currency", p.currency)
                        put("value", value.money())
                        // Values after the last recorded balance are rolled forward with transactions.
                        balance?.let { put("last_balance", it.value.toPlainString()); put("last_balance_date", it.date.toString()) }
                        valuator.productValueEur(p.id, date)?.let { put("value_eur", it.money()) }
                    }
                }
            }
            if (valuator.missingCurrencies.isNotEmpty()) put("missing_fx", valuator.missingCurrencies.joinToString())
        }
    }

    private suspend fun spendingSummary(from: LocalDate, to: LocalDate): JsonObject {
        val products = db.ledgerDao().products().associateBy { it.id }
        val categories = db.setupDao().spendingCategories().associateBy { it.id }
        val fx = fxTable()
        val items = db.ledgerDao().transactions(null, from, to, null, Int.MAX_VALUE)
            .filter { it.kind == TxKind.EXPENSE || it.kind == TxKind.INCOME }
            .map { FlowItem(it.date, it.amount, products[it.productId]?.currency ?: "EUR", it.kind == TxKind.INCOME, it.spendingCategoryId) }
        val income = items.filter { it.isIncome }.fold(BigDecimal.ZERO) { acc, i -> acc + (fx.toEur(i.amount, i.currency, i.date) ?: BigDecimal.ZERO) }
        return buildJsonObject {
            put("from", from.toString()); put("to", to.toString())
            put("income_eur", income.money())
            putJsonArray("spending_by_category") {
                spendingByCategory(items, from, to, fx).forEach { (id, total) ->
                    addJsonObject { put("category", id?.let { categories[it]?.name } ?: "Uncategorised"); put("eur", total.toPlainString()) }
                }
            }
        }
    }

    private suspend fun stageProduct(args: JsonObject): JsonObject {
        val ref = args.string("ref")?.takeIf { it.isNotBlank() } ?: throw ToolError("ref is required")
        if (staged.products.any { it.ref == ref }) throw ToolError("ref '$ref' is already used")
        val sourceId = args.long("source_id") ?: throw ToolError("source_id is required")
        val classId = args.long("asset_class_id") ?: throw ToolError("asset_class_id is required")
        if (db.setupDao().sources().none { it.id == sourceId }) {
            throw ToolError("Source $sourceId does not exist. Sources are created by the user in Setup; ask them to add it.")
        }
        if (db.setupDao().assetClasses().none { it.id == classId }) {
            throw ToolError("Asset class $classId does not exist. Asset classes are created by the user in Setup.")
        }
        val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolError("name is required")
        val currency = currency(args.string("currency"))
        val existing = db.ledgerDao().products().firstOrNull { it.sourceId == sourceId && it.name.equals(name, ignoreCase = true) }
        if (existing != null) throw ToolError("Product '${existing.name}' already exists with id ${existing.id}; use it instead.")
        staged = staged.copy(products = staged.products + NewProduct(ref, sourceId, classId, name, currency, args.string("identifier").orEmpty()))
        return buildJsonObject { put("staged_product_ref", ref) }
    }

    private suspend fun stageSnapshots(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        val accepted = mutableListOf<NewSnapshot>()
        val skipped = mutableListOf<String>()
        items.forEachIndexed { i, element ->
            val item = element.jsonObject
            val pointer = pointer(item, i)
            val date = item.date("date") ?: throw ToolError("items[$i].date is required (YYYY-MM-DD)")
            if (date > today.plusDays(1)) throw ToolError("items[$i].date $date is in the future")
            val value = item.decimal("value") ?: throw ToolError("items[$i].value must be a decimal number")
            val duplicateStaged = (staged.snapshots + accepted).any { it.product == pointer && it.date == date.toString() }
            val duplicateStored = pointer.id != null && db.ledgerDao().snapshotOn(pointer.id, date) != null
            if (duplicateStaged || duplicateStored) { skipped += "items[$i] (${date}): a balance for that day already exists"; return@forEachIndexed }
            accepted += NewSnapshot(
                product = pointer, date = date.toString(), value = value.toPlainString(),
                quantity = item.decimal("quantity")?.toPlainString(), unitPrice = item.decimal("unit_price")?.toPlainString(),
                note = item.string("note").orEmpty(),
            )
        }
        staged = staged.copy(snapshots = staged.snapshots + accepted)
        return buildJsonObject {
            put("staged", accepted.size)
            if (skipped.isNotEmpty()) putJsonArray("skipped") { skipped.forEach { add(it) } }
            checks()?.let { put("checks", it) }
        }
    }

    private suspend fun stageTransactions(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        val categories = db.setupDao().spendingCategories().associateBy { it.id }
        val accepted = mutableListOf<NewTransaction>()
        val skipped = mutableListOf<String>()
        items.forEachIndexed { i, element ->
            val item = element.jsonObject
            val pointer = pointer(item, i)
            val date = item.date("date") ?: throw ToolError("items[$i].date is required (YYYY-MM-DD)")
            if (date > today.plusDays(1)) throw ToolError("items[$i].date $date is in the future")
            val amount = item.decimal("amount") ?: throw ToolError("items[$i].amount must be a signed decimal")
            val kind = parseKind(item.string("kind") ?: throw ToolError("items[$i].kind is required"))
            val categoryId = item.long("spending_category_id")
            if (categoryId != null) {
                val category = categories[categoryId] ?: throw ToolError("items[$i]: spending category $categoryId does not exist")
                val expected = if (kind == TxKind.INCOME) FlowKind.INCOME else FlowKind.EXPENSE
                if (kind != TxKind.EXPENSE && kind != TxKind.INCOME) throw ToolError("items[$i]: only expense and income take a spending category")
                if (category.kind != expected) throw ToolError("items[$i]: '${category.name}' is an ${category.kind.name.lowercase()} category but kind is ${kind.name.lowercase()}")
            }
            val linkId = item.long("link_to_transaction_id")
            if (linkId != null && db.ledgerDao().transaction(linkId) == null) throw ToolError("items[$i]: transaction $linkId does not exist")
            val description = item.string("description")?.trim().orEmpty()
            if (pointer.id != null && item["allow_duplicate"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) {
                val dup = db.ledgerDao().transactions(pointer.id, date, date, null, 50).firstOrNull { it.amount.compareTo(amount) == 0 }
                if (dup != null) { skipped += "items[$i]: looks like existing transaction ${dup.id} (${dup.description}); pass allow_duplicate if it is really new"; return@forEachIndexed }
            }
            accepted += NewTransaction(
                product = pointer, date = date.toString(), amount = amount.toPlainString(), description = description,
                counterparty = item.string("counterparty").orEmpty(), kind = kind.name, spendingCategoryId = categoryId,
                transferKey = item.string("transfer_key"), linkToTransactionId = linkId,
                quantity = item.decimal("quantity")?.toPlainString(), fxRate = item.decimal("fx_rate")?.toPlainString(),
            )
        }
        staged = staged.copy(transactions = staged.transactions + accepted)
        return buildJsonObject {
            put("staged", accepted.size)
            if (skipped.isNotEmpty()) putJsonArray("skipped") { skipped.forEach { add(it) } }
            checks()?.let { put("checks", it) }
        }
    }

    private suspend fun sumTransactions(args: JsonObject): JsonObject {
        val pointer = pointer(args, 0)
        val from = args.date("from") ?: LocalDate.of(1900, 1, 1)
        val to = args.date("to") ?: LocalDate.of(2999, 1, 1)
        val stored = pointer.id?.let { id -> db.ledgerDao().transactions(id, from, to, null, Int.MAX_VALUE) }.orEmpty()
            .map { Triple(it.date, it.amount, it.quantity) }
        val stagedRows = staged.transactions.filter { it.product == pointer }
            .map { Triple(LocalDate.parse(it.date), BigDecimal(it.amount), it.quantity?.let(::BigDecimal)) }
            .filter { it.first in from..to }
        val rows = stored + stagedRows
        return buildJsonObject {
            put("count", rows.size)
            put("recorded", stored.size)
            put("staged", stagedRows.size)
            put("sum_amount", rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.second }.toPlainString())
            put("sum_quantity", rows.mapNotNull { it.third }.fold(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            rows.minOfOrNull { it.first }?.let { put("first_date", it.toString()) }
            rows.maxOfOrNull { it.first }?.let { put("last_date", it.toString()) }
        }
    }

    /** Mismatches between staged balances and the flows behind them, phrased for the model. */
    private suspend fun checks(): JsonArray? {
        val findings = Reconcile.checkStaged(staged, db.backupDao().snapshots(), db.backupDao().transactions())
        val unlinked = Reconcile.unlinkedTrades(staged)
        if (findings.isEmpty() && unlinked == 0) return null
        return buildJsonArray {
            findings.forEach { f ->
                val who = f.product.id?.let { "product $it" } ?: "new product '${f.product.ref}'"
                val what = if (f.measure == Reconcile.Measure.UNITS) "units" else "value"
                val base = f.since?.let { "the balance on $it plus" } ?: "the sum of all"
                add("MISMATCH $who on ${f.date}: staged $what ${f.stated.toPlainString()}, but $base its transactions gives ${f.implied.toPlainString()}. Fix the balance or the transactions, or explain to the user why they differ.")
            }
            if (unlinked > 0) add("$unlinked trade leg(s) have no transfer_key or link_to_transaction_id. Each trade needs a cash leg and a security leg sharing a transfer_key.")
        }
    }

    private suspend fun pointer(item: JsonObject, index: Int): ProductPointer {
        val id = item.long("product_id")
        val ref = item.string("new_product_ref")
        return when {
            id != null -> { requireProduct(id); ProductPointer(id = id) }
            ref != null -> {
                if (staged.products.none { it.ref == ref }) throw ToolError("items[$index]: no staged product with ref '$ref'")
                ProductPointer(ref = ref)
            }
            else -> throw ToolError("items[$index]: give product_id or new_product_ref")
        }
    }

    private suspend fun requireProduct(id: Long) {
        if (db.ledgerDao().product(id) == null) throw ToolError("Product $id does not exist")
    }

    private fun transactionJson(t: com.strata.app.data.db.TransactionEntity) = buildJsonObject {
        put("id", t.id); put("product_id", t.productId); put("date", t.date.toString())
        put("amount", t.amount.toPlainString()); put("description", t.description)
        if (t.counterparty.isNotBlank()) put("counterparty", t.counterparty)
        put("kind", t.kind.name.lowercase())
        t.spendingCategoryId?.let { put("spending_category_id", it) }
        t.transferGroup?.let { put("linked", true) }
        t.quantity?.let { put("quantity", it.toPlainString()) }
    }

    private fun parseKind(value: String): TxKind =
        TxKind.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw ToolError("Unknown kind '$value'. Use one of ${TxKind.entries.joinToString { it.name.lowercase() }}")

    private fun currency(value: String?): String {
        val code = value?.trim()?.uppercase() ?: throw ToolError("currency is required")
        if (!Regex("[A-Z]{3,5}").matches(code)) throw ToolError("currency must be an ISO code like EUR or USD")
        return code
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.decimal(key: String): BigDecimal? {
        val raw = string(key)?.trim()?.replace(" ", "")?.replace("−", "-") ?: return null
        if (',' in raw) throw ToolError("$key: use '.' as the decimal separator and no thousands separators, got '$raw'")
        return raw.toBigDecimalOrNull() ?: throw ToolError("$key must be a decimal number, got '$raw'")
    }

    private fun JsonObject.date(key: String): LocalDate? = string(key)?.let {
        runCatching { LocalDate.parse(it.trim()) }.getOrElse { throw ToolError("$key must be YYYY-MM-DD, got '$it'") }
    }

    private fun BigDecimal.money(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()
}
