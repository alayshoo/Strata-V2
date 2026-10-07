package com.strata.app.ai

import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.TransactionEntity
import com.strata.app.data.db.TxKind
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Checks that balances agree with the flows that lead to them. The model is not trusted to
 * add up rows; this runs on exact decimals, both for staged changes (so the model and the
 * review card see mismatches) and for what is already stored (shown on the product page).
 */
object Reconcile {
    /** A key that names a product whether it exists already or is only staged. */
    data class Key(val id: Long?, val ref: String?)

    data class Point(val date: LocalDate, val value: BigDecimal, val quantity: BigDecimal?)
    data class Flow(val date: LocalDate, val amount: BigDecimal, val quantity: BigDecimal?, val kind: TxKind)

    enum class Measure { VALUE, UNITS }

    data class Finding(
        val product: Key,
        val date: LocalDate,
        val measure: Measure,
        val stated: BigDecimal,
        val implied: BigDecimal,
        /** Null when the flows were summed from the first one on record. */
        val since: LocalDate?,
    ) {
        val difference: BigDecimal get() = stated - implied
    }

    private val TOLERANCE = BigDecimal("0.01")

    /**
     * For each product, every balance is compared with the previous balance plus the flows in
     * between. With no previous balance, flows are summed from the first one, which is right for
     * full-history exports and flags everything else for a human look.
     *
     * Cash-like products are checked on value. Products with unit quantities are checked on
     * units instead, since their value moves with the market.
     */
    fun check(points: Map<Key, List<Point>>, flows: Map<Key, List<Flow>>, only: Set<Pair<Key, LocalDate>>? = null): List<Finding> {
        val out = mutableListOf<Finding>()
        for ((key, series) in points) {
            val productFlows = flows[key].orEmpty().sortedBy { it.date }
            if (productFlows.isEmpty()) continue
            val byUnits = series.any { it.quantity != null } || productFlows.any { it.quantity != null }
            val sorted = series.sortedBy { it.date }
            sorted.forEachIndexed { i, point ->
                if (only != null && (key to point.date) !in only) return@forEachIndexed
                val previous = sorted.getOrNull(i - 1)
                val window = productFlows.filter { f -> f.date <= point.date && (previous == null || f.date > previous.date) }
                if (window.isEmpty() && previous == null) return@forEachIndexed
                if (byUnits) {
                    val stated = point.quantity ?: return@forEachIndexed
                    val base = previous?.quantity ?: BigDecimal.ZERO
                    val implied = base + window.mapNotNull { it.quantity }.fold(BigDecimal.ZERO, BigDecimal::add)
                    if ((stated - implied).abs() > TOLERANCE) out += Finding(key, point.date, Measure.UNITS, stated, implied, previous?.date)
                } else {
                    val base = previous?.value ?: BigDecimal.ZERO
                    val implied = base + window.fold(BigDecimal.ZERO) { acc, f -> acc + f.amount }
                    if ((point.value - implied).abs() > TOLERANCE) out += Finding(key, point.date, Measure.VALUE, point.value, implied, previous?.date)
                }
            }
        }
        return out
    }

    /** Stored and staged rows merged per product, ready for [check]. Staged corrections replace the balances they change. */
    fun inputs(
        changes: ChangeSet,
        storedSnapshots: List<SnapshotEntity>,
        storedTransactions: List<TransactionEntity>,
    ): Pair<Map<Key, List<Point>>, Map<Key, List<Flow>>> {
        val edits = changes.snapshotEdits.associateBy { it.snapshotId }
        val deleted = changes.snapshotDeletions.map { it.snapshotId }.toSet()
        val touched = (changes.snapshots.map { it.product } + changes.transactions.map { it.product })
            .map { Key(it.id, it.ref) }.toSet() +
            (changes.snapshotEdits.map { it.after.productId } + changes.snapshotDeletions.map { it.before.productId }).map { Key(it, null) }
        val points = HashMap<Key, MutableList<Point>>()
        val flows = HashMap<Key, MutableList<Flow>>()
        for (s in storedSnapshots) {
            val key = Key(s.productId, null)
            if (key !in touched || s.id in deleted) continue
            val point = edits[s.id]?.after?.let { Point(LocalDate.parse(it.date), BigDecimal(it.value), it.quantity?.let(::BigDecimal)) }
                ?: Point(s.date, s.value, s.quantity)
            points.getOrPut(key) { mutableListOf() } += point
        }
        for (t in storedTransactions) {
            val key = Key(t.productId, null)
            if (key in touched) flows.getOrPut(key) { mutableListOf() } += Flow(t.date, t.amount, t.quantity, t.kind)
        }
        for (s in changes.snapshots) {
            points.getOrPut(Key(s.product.id, s.product.ref)) { mutableListOf() } +=
                Point(LocalDate.parse(s.date), BigDecimal(s.value), s.quantity?.let(::BigDecimal))
        }
        for (t in changes.transactions) {
            flows.getOrPut(Key(t.product.id, t.product.ref)) { mutableListOf() } +=
                Flow(LocalDate.parse(t.date), BigDecimal(t.amount), t.quantity?.let(::BigDecimal), TxKind.valueOf(t.kind))
        }
        return points to flows
    }

    /**
     * Checks the balances staged in [changes]: new ones, corrected ones, and the next balance after
     * each corrected or removed one, since that is checked against it.
     */
    fun checkStaged(changes: ChangeSet, storedSnapshots: List<SnapshotEntity>, storedTransactions: List<TransactionEntity>): List<Finding> {
        val (points, flows) = inputs(changes, storedSnapshots, storedTransactions)
        val corrected = changes.snapshotEdits.flatMap { listOf(it.before, it.after) } + changes.snapshotDeletions.map { it.before }
        val following = corrected.mapNotNull { r ->
            val key = Key(r.productId, null)
            val date = LocalDate.parse(r.date)
            points[key]?.map { it.date }?.filter { it > date }?.minOrNull()?.let { key to it }
        }
        val only = changes.snapshots.map { Key(it.product.id, it.product.ref) to LocalDate.parse(it.date) }.toSet() +
            changes.snapshotEdits.map { Key(it.after.productId, null) to LocalDate.parse(it.after.date) } + following
        return check(points, flows, only)
    }

    /** Trade legs staged without a link to their other side. */
    fun unlinkedTrades(changes: ChangeSet): Int =
        changes.transactions.count { it.kind == TxKind.TRADE.name && it.transferKey == null && it.linkToTransactionId == null }
}
