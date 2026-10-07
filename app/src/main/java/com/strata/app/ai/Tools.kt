package com.strata.app.ai

import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.SpendingCategoryEntity
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
import kotlinx.serialization.json.JsonObjectBuilder
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
                "get_snapshots", "Balances recorded for one product, with the ids edit_recorded_snapshots and delete_recorded_snapshots take.",
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
                "get_transactions",
                "Recorded transactions, newest first. Always pass product_id and a from/to range covering only the period " +
                    "you need (a statement's period, or a few days around one date); unbounded reads waste your time budget.",
                props(
                    "product_id" to prop("integer", "Only this product."),
                    "from" to prop("string", "Start date, YYYY-MM-DD."),
                    "to" to prop("string", "End date, YYYY-MM-DD."),
                    "kind" to prop("string", "One of: $kinds."),
                    "limit" to prop("integer", "Max rows, up to 200. Default 50."),
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
                            "quantity" to prop("string", "Units bought (+) or sold (−) on a trade's security leg; its amount has the same sign."),
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
                "edit_recorded_snapshots",
                "Stage corrections to balances already recorded, e.g. when the user spots a wrong value in a chart. " +
                    "Get the ids from get_snapshots. In set, pass only the fields to change: date, value, quantity, unit_price, note; " +
                    "null clears quantity, unit_price or note. A balance stays on its product; to move it, delete it and stage a new one. " +
                    "Nothing changes until the user applies the card, which shows the recorded and the corrected values.",
                props(
                    "items" to arraySchema(
                        props(
                            "snapshot_id" to prop("integer", "Id of the recorded balance, from get_snapshots."),
                            "set" to prop("object", "Fields to change, e.g. {\"value\": \"1520.37\"} or {\"date\": \"2026-09-30\"}."),
                        ),
                        listOf("snapshot_id", "set"),
                        "Corrections to stage. If any is invalid, none is staged.",
                    )
                ),
                listOf("items"),
            )
        )
        add(
            tool(
                "delete_recorded_snapshots",
                "Stage the removal of balances already recorded, e.g. a duplicate or one recorded on the wrong product. " +
                    "Get the ids from get_snapshots. Nothing is removed until the user applies the card.",
                props(
                    "snapshot_ids" to buildJsonObject {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                ),
                listOf("snapshot_ids"),
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
        add(
            tool(
                "get_staged_changes",
                "Everything staged so far, including a review card from an earlier turn that is still waiting, plus balance checks. " +
                    "Each item has a staged_id and the same fields as the stage tool that created it. Filter by product and dates to keep it short.",
                props(
                    *productPointer,
                    "from" to prop("string", "Only balances and transactions on or after this date, YYYY-MM-DD."),
                    "to" to prop("string", "Only balances and transactions on or before this date, YYYY-MM-DD."),
                ),
            )
        )
        add(
            tool(
                "update_staged",
                "Correct staged items in place by staged_id, leaving everything else as it is. In set, pass only the fields to change, " +
                    "named as in the tool that staged the item; null clears an optional field. Changing a transaction's kind " +
                    "drops its spending category unless set also gives spending_category_id. A staged product's ref cannot change.",
                props(
                    "items" to arraySchema(
                        props(
                            "staged_id" to prop("integer", "staged_id from get_staged_changes or from the stage tool's result."),
                            "set" to prop("object", "Fields to change, e.g. {\"amount\": \"-12.30\", \"spending_category_id\": 4}."),
                        ),
                        listOf("staged_id", "set"),
                        "Corrections to make. If any is invalid, none is applied.",
                    )
                ),
                listOf("items"),
            )
        )
        add(
            tool(
                "remove_staged",
                "Remove staged items by staged_id. Removing a staged product also removes the balances and transactions staged on it.",
                props(
                    "staged_ids" to buildJsonObject {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                ),
                listOf("staged_ids"),
            )
        )
        add(
            tool(
                "clear_staged_changes",
                "Discard everything staged, including a carried-over review card, to start over. To fix a few items use update_staged or remove_staged instead.",
            )
        )
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

    fun reset(initial: ChangeSet = ChangeSet()) { staged = initial.withIds() }

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
        "edit_recorded_snapshots" -> "Staged balance corrections"
        "delete_recorded_snapshots" -> "Staged balance removals"
        "sum_transactions" -> "Added up transactions"
        "calculate" -> "Calculated"
        "get_staged_changes" -> "Reviewed staged changes"
        "update_staged" -> "Corrected staged changes"
        "remove_staged" -> "Removed staged changes"
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
                        if (s.note.isNotBlank()) put("note", s.note)
                        staged.snapshotEdits.firstOrNull { it.snapshotId == s.id }?.let { put("staged_correction", it.id) }
                        staged.snapshotDeletions.firstOrNull { it.snapshotId == s.id }?.let { put("staged_removal", it.id) }
                    }
                }
            }
        }
        "get_transactions" -> {
            val kind = args.string("kind")?.let(::parseKind)
            val limit = (args.long("limit") ?: 50).toInt().coerceIn(1, 200)
            // One extra row tells us whether the range was cut off.
            val rows = db.ledgerDao().transactions(
                args.long("product_id"), args.date("from") ?: LocalDate.of(1900, 1, 1),
                args.date("to") ?: LocalDate.of(2999, 1, 1), kind, limit + 1,
            )
            buildJsonObject {
                putJsonArray("transactions") { rows.take(limit).forEach { t -> add(transactionJson(t)) } }
                if (rows.size > limit) put("truncated", "Only the newest $limit are shown. Narrow from/to or pass product_id instead of raising the limit.")
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
        "edit_recorded_snapshots" -> editRecordedSnapshots(args)
        "delete_recorded_snapshots" -> deleteRecordedSnapshots(args)
        "link_transfer" -> {
            val ids = args["transaction_ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
            requireLinkable(ids, "")
            val link = TransferLink(ids, newId())
            staged = staged.copy(links = staged.links + link)
            buildJsonObject { put("staged", "link of ${ids.joinToString()}"); put("staged_id", link.id) }
        }
        "sum_transactions" -> sumTransactions(args)
        "calculate" -> {
            val expression = args.string("expression") ?: throw ToolError("expression is required")
            val result = try { Calculator.evaluate(expression) } catch (e: Calculator.CalcError) { throw ToolError(e.message ?: "Invalid expression") }
            buildJsonObject { put("expression", expression); put("result", result.toPlainString()) }
        }
        "get_staged_changes" -> stagedChanges(args)
        "update_staged" -> updateStaged(args)
        "remove_staged" -> removeStaged(args)
        "clear_staged_changes" -> {
            // Ids keep counting up so an id from before the clear never names a new item.
            staged = ChangeSet(fileNames = staged.fileNames, nextId = staged.nextId)
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
            products.map { ValuedProduct(it.id, it.assetClassId, it.currency, it.sourceId) },
            snapshots.map { ValuePoint(it.productId, it.date, it.value, it.quantity) },
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

    private fun newId(): Int = staged.nextId.also { staged = staged.copy(nextId = it + 1) }

    private suspend fun stageProduct(args: JsonObject): JsonObject {
        val ref = args.string("ref")?.takeIf { it.isNotBlank() } ?: throw ToolError("ref is required")
        if (staged.products.any { it.ref == ref }) throw ToolError("ref '$ref' is already used")
        val product = parseProduct(args, ref).copy(id = newId())
        staged = staged.copy(products = staged.products + product)
        return buildJsonObject { put("staged_product_ref", ref); put("staged_id", product.id) }
    }

    private suspend fun stageSnapshots(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        val accepted = mutableListOf<NewSnapshot>()
        val skipped = mutableListOf<String>()
        items.forEachIndexed { i, element ->
            val snapshot = parseSnapshot(element.jsonObject, "items[$i]")
            val clash = snapshotClash(snapshot, staged.snapshots + accepted)
            if (clash != null) { skipped += "items[$i] (${snapshot.date}): $clash"; return@forEachIndexed }
            accepted += snapshot
        }
        val added = accepted.map { it.copy(id = newId()) }
        staged = staged.copy(snapshots = staged.snapshots + added)
        return stagedResult(added.map { it.id }, skipped)
    }

    private suspend fun stageTransactions(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        val categories = db.setupDao().spendingCategories().associateBy { it.id }
        val accepted = mutableListOf<NewTransaction>()
        val skipped = mutableListOf<String>()
        items.forEachIndexed { i, element ->
            val item = element.jsonObject
            val transaction = parseTransaction(item, "items[$i]", categories)
            val clash = transactionClash(transaction, item)
            if (clash != null) { skipped += "items[$i]: $clash"; return@forEachIndexed }
            accepted += transaction
        }
        val added = accepted.map { it.copy(id = newId()) }
        staged = staged.copy(transactions = staged.transactions + added)
        return stagedResult(added.map { it.id }, skipped)
    }

    private suspend fun stagedResult(ids: List<Int>, skipped: List<String>) = buildJsonObject {
        put("staged", ids.size)
        if (ids.isNotEmpty()) putJsonArray("staged_ids") { ids.forEach { add(it) } }
        if (skipped.isNotEmpty()) putJsonArray("skipped") { skipped.forEach { add(it) } }
        checks()?.let { put("checks", it) }
    }

    private suspend fun parseProduct(args: JsonObject, ref: String, at: String = ""): NewProduct {
        val sourceId = args.long("source_id") ?: throw ToolError("${at}source_id is required")
        val classId = args.long("asset_class_id") ?: throw ToolError("${at}asset_class_id is required")
        if (db.setupDao().sources().none { it.id == sourceId }) {
            throw ToolError("${at}Source $sourceId does not exist. Sources are created by the user in Setup; ask them to add it.")
        }
        if (db.setupDao().assetClasses().none { it.id == classId }) {
            throw ToolError("${at}Asset class $classId does not exist. Asset classes are created by the user in Setup.")
        }
        val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolError("${at}name is required")
        val currency = currency(args.string("currency"))
        val existing = db.ledgerDao().products().firstOrNull { it.sourceId == sourceId && it.name.equals(name, ignoreCase = true) }
        if (existing != null) throw ToolError("${at}Product '${existing.name}' already exists with id ${existing.id}; use it instead.")
        return NewProduct(ref, sourceId, classId, name, currency, args.string("identifier").orEmpty())
    }

    private suspend fun parseSnapshot(item: JsonObject, at: String): NewSnapshot {
        val pointer = pointer(item, at)
        val date = item.date("date") ?: throw ToolError("$at.date is required (YYYY-MM-DD)")
        if (date > today.plusDays(1)) throw ToolError("$at.date $date is in the future")
        val value = item.decimal("value") ?: throw ToolError("$at.value must be a decimal number")
        return NewSnapshot(
            product = pointer, date = date.toString(), value = value.toPlainString(),
            quantity = item.decimal("quantity")?.toPlainString(), unitPrice = item.decimal("unit_price")?.toPlainString(),
            note = item.string("note").orEmpty(),
        )
    }

    /** Why [snapshot] can't be staged next to [others], or null when it can. */
    private suspend fun snapshotClash(snapshot: NewSnapshot, others: List<NewSnapshot>): String? {
        val duplicateStaged = others.any { it.product == snapshot.product && it.date == snapshot.date }
        val productId = snapshot.product.id ?: return if (duplicateStaged) "a balance for that day already exists" else null
        val stored = db.ledgerDao().snapshotOn(productId, LocalDate.parse(snapshot.date))
        // A recorded balance staged for removal frees its day; one being corrected keeps it, as does one corrected onto it.
        val duplicateStored = stored != null && staged.snapshotDeletions.none { it.snapshotId == stored.id }
        val correctedOnto = staged.snapshotEdits.any { it.after.productId == productId && it.after.date == snapshot.date }
        return when {
            duplicateStaged || correctedOnto -> "a balance for that day already exists"
            duplicateStored -> "balance ${stored!!.id} is already recorded for that day; correct it with edit_recorded_snapshots instead"
            else -> null
        }
    }

    private suspend fun parseTransaction(item: JsonObject, at: String, categories: Map<Long, SpendingCategoryEntity>): NewTransaction {
        val pointer = pointer(item, at)
        val date = item.date("date") ?: throw ToolError("$at.date is required (YYYY-MM-DD)")
        if (date > today.plusDays(1)) throw ToolError("$at.date $date is in the future")
        val amount = item.decimal("amount") ?: throw ToolError("$at.amount must be a signed decimal")
        val kind = parseKind(item.string("kind") ?: throw ToolError("$at.kind is required"))
        val categoryId = item.long("spending_category_id")
        if (categoryId != null) {
            val category = categories[categoryId] ?: throw ToolError("$at: spending category $categoryId does not exist")
            val expected = if (kind == TxKind.INCOME) FlowKind.INCOME else FlowKind.EXPENSE
            if (kind != TxKind.EXPENSE && kind != TxKind.INCOME) throw ToolError("$at: only expense and income take a spending category")
            if (category.kind != expected) throw ToolError("$at: '${category.name}' is an ${category.kind.name.lowercase()} category but kind is ${kind.name.lowercase()}")
        }
        val linkId = item.long("link_to_transaction_id")
        if (linkId != null && db.ledgerDao().transaction(linkId) == null) throw ToolError("$at: transaction $linkId does not exist")
        val quantity = item.decimal("quantity")?.takeIf { it.signum() != 0 }
        if (quantity != null) {
            if (amount.signum() != 0 && amount.signum() != quantity.signum()) throw ToolError(
                "$at: quantity and amount have opposite signs. On a trade's security leg a buy adds units and value (both positive) " +
                    "and a sale removes both (both negative); the cash leg carries the opposite amount and no quantity. " +
                    "A split or spin-off moves units on the security's own product with amount 0"
            )
            // A product whose balances are recorded without units holds cash; units there would be valued as a holding.
            val productId = pointer.id
            if (productId != null) {
                val balances = db.ledgerDao().snapshotsFor(productId, LocalDate.of(1900, 1, 1), today)
                if (balances.isNotEmpty() && balances.none { it.quantity != null }) throw ToolError(
                    "$at: product $productId holds cash (its balances carry no units), so it takes no quantity; put the units on the security's own product"
                )
            }
        }
        return NewTransaction(
            product = pointer, date = date.toString(), amount = amount.toPlainString(), description = item.string("description")?.trim().orEmpty(),
            counterparty = item.string("counterparty").orEmpty(), kind = kind.name, spendingCategoryId = categoryId,
            transferKey = item.string("transfer_key"), linkToTransactionId = linkId,
            quantity = item.decimal("quantity")?.toPlainString(), fxRate = item.decimal("fx_rate")?.toPlainString(),
        )
    }

    /** A recorded transaction that [transaction] looks like, unless [item] says it is really new. */
    private suspend fun transactionClash(transaction: NewTransaction, item: JsonObject): String? {
        val productId = transaction.product.id ?: return null
        if (item["allow_duplicate"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        val date = LocalDate.parse(transaction.date)
        val amount = BigDecimal(transaction.amount)
        val dup = db.ledgerDao().transactions(productId, date, date, null, 50).firstOrNull { it.amount.compareTo(amount) == 0 } ?: return null
        return "looks like existing transaction ${dup.id} (${dup.description}); pass allow_duplicate if it is really new"
    }

    private suspend fun requireLinkable(ids: List<Long>, at: String) {
        if (ids.size < 2) throw ToolError("${at}Provide at least two transaction ids")
        ids.forEach { if (db.ledgerDao().transaction(it) == null) throw ToolError("${at}Transaction $it does not exist") }
    }

    // ---------- Correcting balances already recorded ----------

    private suspend fun editRecordedSnapshots(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        if (items.isEmpty()) throw ToolError("items is empty")
        val before = staged
        val ids = try {
            items.mapIndexed { i, element ->
                val item = element.jsonObject
                val snapshotId = item.long("snapshot_id") ?: throw ToolError("items[$i].snapshot_id is required")
                val set = item["set"] as? JsonObject ?: throw ToolError("items[$i].set must be an object with the fields to change")
                stageSnapshotEdit(snapshotId, set, "items[$i]")
            }
        } catch (e: Exception) {
            staged = before
            throw e
        }
        return stagedResult(ids.distinct(), emptyList())
    }

    /** Stages [set] on recorded balance [snapshotId], on top of a correction already staged for it. Returns its staged id. */
    private suspend fun stageSnapshotEdit(snapshotId: Long, set: JsonObject, at: String): Int {
        val row = db.ledgerDao().snapshot(snapshotId)
            ?: throw ToolError("$at: balance $snapshotId does not exist. get_snapshots lists a product's balances with their ids.")
        staged.snapshotDeletions.firstOrNull { it.snapshotId == snapshotId }?.let {
            throw ToolError("$at: balance $snapshotId is staged for removal (staged_id ${it.id}); remove_staged that first to correct it instead")
        }
        val unknown = set.keys - EDITABLE_SNAPSHOT_FIELDS
        if ("product_id" in unknown || "new_product_ref" in unknown) {
            throw ToolError("$at: a balance can't move to another product; delete it with delete_recorded_snapshots and stage a new one there")
        }
        if (unknown.isNotEmpty()) throw ToolError("$at: can't set ${unknown.joinToString()}; a balance has ${EDITABLE_SNAPSHOT_FIELDS.joinToString()}")
        val existing = staged.snapshotEdits.firstOrNull { it.snapshotId == snapshotId }
        val recorded = row.recorded()
        val base = existing?.after ?: recorded
        val parsed = parseSnapshot(overlay(recordedArgs(base), set), at)
        val after = RecordedSnapshot(row.productId, parsed.date, parsed.value, parsed.quantity, parsed.unitPrice, parsed.note)
        if (after.sameAs(recorded)) {
            val hint = existing?.let { "; remove_staged ${it.id} to drop the staged correction" }.orEmpty()
            throw ToolError("$at: that leaves balance $snapshotId as it is recorded$hint")
        }
        if (after.date != recorded.date) {
            val date = LocalDate.parse(after.date)
            val other = db.ledgerDao().snapshotOn(row.productId, date)
            if (other != null && other.id != snapshotId && staged.snapshotDeletions.none { it.snapshotId == other.id }) {
                throw ToolError("$at: balance ${other.id} is already recorded on $date; correct or delete that one instead")
            }
            val stagedOnDay = staged.snapshots.any { it.product == ProductPointer(id = row.productId) && it.date == after.date } ||
                staged.snapshotEdits.any { it.snapshotId != snapshotId && it.after.productId == row.productId && it.after.date == after.date }
            if (stagedOnDay) throw ToolError("$at: another balance is staged for $date on that product")
        }
        val edit = SnapshotEdit(snapshotId, recorded, after, existing?.id ?: newId())
        staged = staged.copy(
            snapshotEdits = if (existing != null) staged.snapshotEdits.map { if (it.id == edit.id) edit else it } else staged.snapshotEdits + edit
        )
        return edit.id
    }

    private suspend fun deleteRecordedSnapshots(args: JsonObject): JsonObject {
        val ids = args["snapshot_ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty().distinct()
        if (ids.isEmpty()) throw ToolError("snapshot_ids is required")
        val rows = ids.map {
            db.ledgerDao().snapshot(it) ?: throw ToolError("Balance $it does not exist. get_snapshots lists a product's balances with their ids.")
        }
        // Removing a balance replaces a correction staged for it.
        val replaced = staged.snapshotEdits.filter { it.snapshotId in ids }
        val added = rows.filter { r -> staged.snapshotDeletions.none { it.snapshotId == r.id } }.map { SnapshotDeletion(it.id, it.recorded(), newId()) }
        staged = staged.copy(snapshotEdits = staged.snapshotEdits - replaced.toSet(), snapshotDeletions = staged.snapshotDeletions + added)
        val already = staged.snapshotDeletions.filter { it.snapshotId in ids && it !in added }
        return buildJsonObject {
            put("staged", added.size)
            if (added.isNotEmpty()) putJsonArray("staged_ids") { added.forEach { add(it.id) } }
            if (already.isNotEmpty()) putJsonArray("already_staged") { already.forEach { add(it.id) } }
            if (replaced.isNotEmpty()) putJsonArray("replaced_corrections") { replaced.forEach { add(it.id) } }
            checks()?.let { put("checks", it) }
        }
    }

    private fun com.strata.app.data.db.SnapshotEntity.recorded() = RecordedSnapshot(
        productId, date.toString(), value.toPlainString(), quantity?.toPlainString(), unitPrice?.toPlainString(), note,
    )

    /** Same balance, comparing decimals by value so "1520.3" and "1520.30" match. */
    private fun RecordedSnapshot.sameAs(other: RecordedSnapshot): Boolean {
        fun eq(a: String?, b: String?) = if (a == null || b == null) a == b else BigDecimal(a).compareTo(BigDecimal(b)) == 0
        return productId == other.productId && date == other.date && eq(value, other.value) &&
            eq(quantity, other.quantity) && eq(unitPrice, other.unitPrice) && note == other.note
    }

    // ---------- Reviewing and correcting what is staged ----------

    private suspend fun stagedChanges(args: JsonObject): JsonObject {
        val pointer = args.long("product_id")?.let { ProductPointer(id = it) } ?: args.string("new_product_ref")?.let { ProductPointer(ref = it) }
        val from = args.date("from")
        val to = args.date("to")
        fun keep(product: ProductPointer, date: String): Boolean {
            if (pointer != null && product != pointer) return false
            val d = LocalDate.parse(date)
            return (from == null || d >= from) && (to == null || d <= to)
        }
        val snapshots = staged.snapshots.filter { keep(it.product, it.date) }
        val transactions = staged.transactions.filter { keep(it.product, it.date) }
        val edits = staged.snapshotEdits.filter { keep(ProductPointer(id = it.after.productId), it.after.date) || keep(ProductPointer(id = it.before.productId), it.before.date) }
        val deletions = staged.snapshotDeletions.filter { keep(ProductPointer(id = it.before.productId), it.before.date) }
        return buildJsonObject {
            putJsonObject("totals") { putTotals() }
            if (pointer != null || from != null || to != null) {
                put("shown", "${snapshots.size} balances, ${transactions.size} transactions, ${edits.size} corrections and ${deletions.size} removals match the filter")
            }
            putJsonArray("products") { staged.products.forEach { add(productArgs(it)) } }
            putJsonArray("snapshots") { snapshots.forEach { add(snapshotArgs(it)) } }
            putJsonArray("transactions") { transactions.forEach { add(transactionArgs(it)) } }
            putJsonArray("links") { staged.links.forEach { add(linkArgs(it)) } }
            if (staged.snapshotEdits.isNotEmpty()) putJsonArray("recorded_snapshot_corrections") { edits.forEach { add(editArgs(it)) } }
            if (staged.snapshotDeletions.isNotEmpty()) putJsonArray("recorded_snapshot_removals") { deletions.forEach { add(deletionArgs(it)) } }
            checks()?.let { put("checks", it) }
        }
    }

    private fun JsonObjectBuilder.putTotals() {
        put("products", staged.products.size); put("snapshots", staged.snapshots.size)
        put("transactions", staged.transactions.size); put("links", staged.links.size)
        if (staged.snapshotEdits.isNotEmpty()) put("recorded_snapshot_corrections", staged.snapshotEdits.size)
        if (staged.snapshotDeletions.isNotEmpty()) put("recorded_snapshot_removals", staged.snapshotDeletions.size)
    }

    private suspend fun updateStaged(args: JsonObject): JsonObject {
        val items = args["items"]?.jsonArray ?: throw ToolError("items is required")
        if (items.isEmpty()) throw ToolError("items is empty")
        val before = staged
        try {
            val categories = db.setupDao().spendingCategories().associateBy { it.id }
            items.forEachIndexed { i, element ->
                val item = element.jsonObject
                val id = item.long("staged_id")?.toInt() ?: throw ToolError("items[$i].staged_id is required")
                val set = item["set"] as? JsonObject ?: throw ToolError("items[$i].set must be an object with the fields to change")
                updateOne(id, JsonObject(set - "staged_id"), "staged item $id", categories)
            }
        } catch (e: Exception) {
            // All or nothing, so a half-applied batch never leaves the model guessing what changed.
            staged = before
            throw e
        }
        return buildJsonObject {
            put("updated", items.size)
            checks()?.let { put("checks", it) }
        }
    }

    private suspend fun updateOne(id: Int, set: JsonObject, at: String, categories: Map<Long, SpendingCategoryEntity>) {
        staged.transactions.firstOrNull { it.id == id }?.let { old ->
            val base = transactionArgs(old)
            val kindChanged = set["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull?.trim()?.equals(old.kind, ignoreCase = true) != true } == true
            val merged = overlay(if (kindChanged && "spending_category_id" !in set) JsonObject(base - "spending_category_id") else base, set)
            val updated = parseTransaction(merged, at, categories).copy(id = id)
            val moved = updated.product != old.product || updated.date != old.date || BigDecimal(updated.amount).compareTo(BigDecimal(old.amount)) != 0
            if (moved) transactionClash(updated, set)?.let { throw ToolError("$at: $it") }
            staged = staged.copy(transactions = staged.transactions.map { if (it.id == id) updated else it })
            return
        }
        staged.snapshots.firstOrNull { it.id == id }?.let { old ->
            val updated = parseSnapshot(overlay(snapshotArgs(old), set), at).copy(id = id)
            if (updated.product != old.product || updated.date != old.date) {
                snapshotClash(updated, staged.snapshots.filter { it.id != id })?.let { throw ToolError("$at (${updated.date}): $it") }
            }
            staged = staged.copy(snapshots = staged.snapshots.map { if (it.id == id) updated else it })
            return
        }
        staged.products.firstOrNull { it.id == id }?.let { old ->
            set.string("ref")?.let { if (it != old.ref) throw ToolError("$at: a staged product's ref can't change; remove it and stage it again") }
            val updated = parseProduct(overlay(productArgs(old), set), old.ref, "$at: ").copy(id = id)
            staged = staged.copy(products = staged.products.map { if (it.id == id) updated else it })
            return
        }
        staged.snapshotEdits.firstOrNull { it.id == id }?.let {
            stageSnapshotEdit(it.snapshotId, set, at)
            return
        }
        if (staged.snapshotDeletions.any { it.id == id }) {
            throw ToolError("$at: a removal has nothing to change; remove_staged $id keeps the balance, then edit_recorded_snapshots corrects it")
        }
        staged.links.firstOrNull { it.id == id }?.let {
            val ids = set["transaction_ids"]?.jsonArray?.mapNotNull { e -> (e as? JsonPrimitive)?.longOrNull }
                ?: throw ToolError("$at: a link only has transaction_ids to change")
            requireLinkable(ids, "$at: ")
            staged = staged.copy(links = staged.links.map { l -> if (l.id == id) TransferLink(ids, id) else l })
            return
        }
        throw ToolError("$at: nothing staged has that id. Call get_staged_changes for the current ids.")
    }

    private suspend fun removeStaged(args: JsonObject): JsonObject {
        val ids = args["staged_ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }.orEmpty().toSet()
        if (ids.isEmpty()) throw ToolError("staged_ids is required")
        val unknown = ids - staged.ids.toSet()
        if (unknown.isNotEmpty()) throw ToolError("Nothing staged has id ${unknown.joinToString()}. Call get_staged_changes for the current ids.")
        // Rows staged on a removed product would point at nothing.
        val refs = staged.products.filter { it.id in ids }.map { it.ref }.toSet()
        fun orphaned(p: ProductPointer) = p.ref != null && p.ref in refs
        val cascaded = staged.snapshots.filter { it.id !in ids && orphaned(it.product) }.map { it.id } +
            staged.transactions.filter { it.id !in ids && orphaned(it.product) }.map { it.id }
        val gone = ids + cascaded
        staged = staged.copy(
            products = staged.products.filter { it.id !in gone },
            snapshots = staged.snapshots.filter { it.id !in gone },
            transactions = staged.transactions.filter { it.id !in gone },
            links = staged.links.filter { it.id !in gone },
            snapshotEdits = staged.snapshotEdits.filter { it.id !in gone },
            snapshotDeletions = staged.snapshotDeletions.filter { it.id !in gone },
        )
        return buildJsonObject {
            put("removed", ids.size)
            if (cascaded.isNotEmpty()) putJsonArray("also_removed_with_their_product") { cascaded.forEach { add(it) } }
            putJsonObject("remaining") { putTotals() }
            checks()?.let { put("checks", it) }
        }
    }

    /** [base] with [set] on top. A product is named by id or by ref, so setting one drops the other. */
    private fun overlay(base: JsonObject, set: JsonObject): JsonObject {
        val merged = base.toMutableMap()
        if ("product_id" in set) merged.remove("new_product_ref")
        if ("new_product_ref" in set) merged.remove("product_id")
        merged.putAll(set)
        return JsonObject(merged)
    }

    // Staged items in the same shape as the stage tools' arguments, so the model can read and correct them alike.

    private fun JsonObjectBuilder.putPointer(p: ProductPointer) {
        p.id?.let { put("product_id", it) }
        p.ref?.let { put("new_product_ref", it) }
    }

    private fun productArgs(p: NewProduct) = buildJsonObject {
        put("staged_id", p.id); put("ref", p.ref); put("source_id", p.sourceId); put("asset_class_id", p.assetClassId)
        put("name", p.name); put("currency", p.currency)
        if (p.identifier.isNotBlank()) put("identifier", p.identifier)
    }

    private fun snapshotArgs(s: NewSnapshot) = buildJsonObject {
        put("staged_id", s.id); putPointer(s.product); put("date", s.date); put("value", s.value)
        s.quantity?.let { put("quantity", it) }
        s.unitPrice?.let { put("unit_price", it) }
        if (s.note.isNotBlank()) put("note", s.note)
    }

    private fun transactionArgs(t: NewTransaction) = buildJsonObject {
        put("staged_id", t.id); putPointer(t.product); put("date", t.date); put("amount", t.amount)
        put("description", t.description)
        if (t.counterparty.isNotBlank()) put("counterparty", t.counterparty)
        put("kind", t.kind.lowercase())
        t.spendingCategoryId?.let { put("spending_category_id", it) }
        t.transferKey?.let { put("transfer_key", it) }
        t.linkToTransactionId?.let { put("link_to_transaction_id", it) }
        t.quantity?.let { put("quantity", it) }
        t.fxRate?.let { put("fx_rate", it) }
    }

    private fun linkArgs(l: TransferLink) = buildJsonObject {
        put("staged_id", l.id)
        putJsonArray("transaction_ids") { l.transactionIds.forEach { add(it) } }
    }

    private fun recordedArgs(r: RecordedSnapshot) = buildJsonObject {
        put("product_id", r.productId); put("date", r.date); put("value", r.value)
        r.quantity?.let { put("quantity", it) }
        r.unitPrice?.let { put("unit_price", it) }
        if (r.note.isNotBlank()) put("note", r.note)
    }

    private fun editArgs(e: SnapshotEdit) = buildJsonObject {
        put("staged_id", e.id); put("snapshot_id", e.snapshotId)
        put("recorded", recordedArgs(e.before)); put("corrected", recordedArgs(e.after))
    }

    private fun deletionArgs(d: SnapshotDeletion) = buildJsonObject {
        put("staged_id", d.id); put("snapshot_id", d.snapshotId); put("recorded", recordedArgs(d.before))
    }

    private suspend fun sumTransactions(args: JsonObject): JsonObject {
        val pointer = pointer(args, "arguments")
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

    private suspend fun pointer(item: JsonObject, at: String): ProductPointer {
        val id = item.long("product_id")
        val ref = item.string("new_product_ref")
        return when {
            id != null -> { requireProduct(id); ProductPointer(id = id) }
            ref != null -> {
                if (staged.products.none { it.ref == ref }) throw ToolError("$at: no staged product with ref '$ref'")
                ProductPointer(ref = ref)
            }
            else -> throw ToolError("$at: give product_id or new_product_ref")
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

    private companion object {
        val EDITABLE_SNAPSHOT_FIELDS = listOf("date", "value", "quantity", "unit_price", "note")
    }
}
