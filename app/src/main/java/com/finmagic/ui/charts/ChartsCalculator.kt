package com.finmagic.ui.charts

import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.CardRepository
import com.finmagic.data.sms.parsers.TxnCategory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class ChartGranularity(val label: String) {
    DAILY("Daily"),
    MONTHLY("Month"),
    YEARLY("Year")
}

data class NetAssetPoint(
    val label: String,
    val fullLabel: String,
    val netAsset: Double,
    val assets: Double,
    val liabilities: Double,
    val periodEndMillis: Long
)

data class CashflowPoint(
    val label: String,
    val fullLabel: String,
    val income: Double,
    val expense: Double
)

data class CategoryChartSlice(
    val category: String,
    val amount: Double,
    val prevAmount: Double,
    val percent: Float
)

data class AllocationSlice(
    val label: String,
    val amount: Double,
    val isLiability: Boolean
)

data class PeriodComparisonSnapshot(
    val label: String,
    val netAsset: Double,
    val assets: Double,
    val liabilities: Double,
    val income: Double,
    val expense: Double,
    val netCashflow: Double,
    val topCategories: List<Pair<String, Double>>
)

object ChartsCalculator {

    private val shortMonthFmt = SimpleDateFormat("MMM", Locale.US)
    private val monthYearFmt = SimpleDateFormat("MMM yyyy", Locale.US)
    private val dayMonthFmt = SimpleDateFormat("dd MMM", Locale.US)

    fun startOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun endOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

    fun startOfMonth(year: Int, month: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun endOfMonth(year: Int, month: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis - 1L
    }

    fun startOfYear(year: Int): Long = startOfMonth(year, Calendar.JANUARY)

    fun endOfYear(year: Int): Long = endOfMonth(year, Calendar.DECEMBER)

    fun computeNetAssetAt(
        cutoffMillis: Long,
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        nowMillis: Long = System.currentTimeMillis(),
        cards: List<CreditCard> = emptyList(),
        cardTxns: List<CardTransaction> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): Triple<Double, Double, Double> {
        val countedAccounts = accounts.filter { !it.isArchived && it.includeInTotal }
        var assets = 0.0
        var accountLiabilities = 0.0

        for (account in countedAccounts) {
            val allTxns = txnsByAccount[account.id].orEmpty()
            val balance = if (account.kind == Account.KIND_CASH) {
                computeCashBalanceAt(cutoffMillis, allTxns)
            } else {
                computeNonCashAccountBalanceAt(cutoffMillis, account, allTxns)
            }
            if (account.isLiability) {
                accountLiabilities += abs(balance)
            } else {
                assets += balance
            }
        }

        val todayStart = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val cardIds = cards.map { it.id }.toSet()
        val billsByCard = bills.groupBy { it.cardId }

        val knownCardOutstanding = cards.sumOf { card ->
            CardRepository.computeCardOutstanding(
                card = card,
                cardBills = billsByCard[card.id].orEmpty(),
                allCardTxns = cardTxns,
                allPayments = cardPayments,
                asOfMillis = cutoffMillis,
                nowMillis = nowMillis
            )
        }

        val orphanBills = bills.filter { it.cardId !in cardIds }
        val orphanCardLiabilities = if (cutoffMillis >= todayStart) {
            orphanBills.filter { !it.isPaid && !it.isSuperseded }
                .sumOf { max(0.0, it.totalDue - it.paidAmount) }
        } else {
            orphanBills
                .filter { !it.isSuperseded && it.detectedAt <= cutoffMillis }
                .groupBy { it.cardId to it.currency.ifBlank { "BDT" }.uppercase(Locale.US) }
                .values
                .mapNotNull { group ->
                    group.maxWithOrNull(compareBy<BillWithCard>({ it.detectedAt }, { it.billId }))
                }
                .sumOf { bill ->
                    val paidAt = bill.paidAt
                    when {
                        !bill.isPaid -> {
                            val effectivePaid = if (paidAt != null && paidAt <= cutoffMillis) bill.paidAmount else 0.0
                            max(0.0, bill.totalDue - effectivePaid)
                        }
                        paidAt != null && paidAt > cutoffMillis -> bill.totalDue
                        else -> 0.0
                    }
                }
        }

        val totalLiabilities = accountLiabilities + knownCardOutstanding + orphanCardLiabilities
        val netAsset = assets - totalLiabilities
        return Triple(netAsset, assets, totalLiabilities)
    }

    internal fun computeNonCashAccountBalanceAt(
        cutoffMillis: Long,
        account: Account,
        allTxns: List<AccountTransaction>
    ): Double {
        if (allTxns.isEmpty()) return 0.0
        val sortedAll = allTxns.sortedWith(compareBy({ it.occurredAt }, { it.id }))
        val upToCutoff = sortedAll.filter { it.occurredAt <= cutoffMillis }
        val realTxns = sortedAll.filter { it.category != TxnCategory.DIRECT_SET }
        val hasRealAnchor = realTxns.any { it.balanceAfter != null }

        if (upToCutoff.isEmpty()) {
            // Before the earliest SMS in the inbox, an SMS with a known balanceAfter
            // implies the account already held `before` prior to that transaction.
            if (!account.isLiability && hasRealAnchor && sortedAll.first().category != TxnCategory.DIRECT_SET) {
                val trails = AccountRepository.computeBalanceTrails(realTxns)
                val firstReal = realTxns.firstOrNull()
                if (firstReal != null) {
                    return max(0.0, trails[firstReal.id]?.before ?: 0.0)
                }
            }
            return 0.0
        }

        // Run balance trails on the full timeline so later SMS balanceAfter anchors
        // backfill earlier unanchored debits/credits instead of dropping to negative.
        val trails = AccountRepository.computeBalanceTrails(sortedAll)
        val lastAtCutoff = upToCutoff.last()
        val balance = trails[lastAtCutoff.id]?.after ?: AccountRepository.computeAccountBalance(upToCutoff)
        val hasFutureDirectSet = sortedAll.any {
            it.category == TxnCategory.DIRECT_SET && it.occurredAt > cutoffMillis
        }
        return if (!account.isLiability && !hasRealAnchor && hasFutureDirectSet) {
            max(0.0, balance)
        } else {
            balance
        }
    }

    internal fun computeCashBalanceAt(
        cutoffMillis: Long,
        allTxns: List<AccountTransaction>
    ): Double {
        val txnsUpToCutoff = allTxns.filter { it.occurredAt <= cutoffMillis }
        val latestDirectSet = allTxns
            .filter { it.category == TxnCategory.DIRECT_SET }
            .maxWithOrNull(compareBy({ it.occurredAt }, { it.id }))
            ?: return AccountRepository.effectiveBalance(txnsUpToCutoff)

        val setAt = latestDirectSet.occurredAt
        if (cutoffMillis >= setAt) {
            return AccountRepository.effectiveBalance(txnsUpToCutoff)
        }

        // Before a DirectSet ("Without Adjustment Entry") correction, treat the written-off
        // portion of pre-adjustment ATM/wallet cash inflows as already spent so old withdrawals
        // do not inflate historical Net Asset.
        val txnsAtSet = allTxns.filter { it.occurredAt <= setAt }
        val targetAtSet = AccountRepository.effectiveBalance(txnsAtSet)
        val rawAtSet = AccountRepository.ledgerBalance(txnsAtSet)
        val excess = rawAtSet - targetAtSet
        if (excess <= 0.005) {
            return AccountRepository.effectiveBalance(txnsUpToCutoff)
        }

        val isPreSetAutoCashIn: (AccountTransaction) -> Boolean = { txn ->
            txn.category != TxnCategory.DIRECT_SET &&
                txn.direction == AccountTransaction.DIRECTION_IN &&
                (!txn.isManual || txn.transferId != null)
        }
        val autoInTotalAtSet = txnsAtSet.filter(isPreSetAutoCashIn).sumOf { it.amount }
        val spentFraction = if (autoInTotalAtSet > 0.0) {
            (excess / autoInTotalAtSet).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val adjusted = txnsUpToCutoff
            .filter { it.category != TxnCategory.DIRECT_SET }
            .sumOf { txn ->
                when {
                    isPreSetAutoCashIn(txn) -> txn.amount * (1.0 - spentFraction)
                    txn.direction == AccountTransaction.DIRECTION_IN -> txn.amount
                    else -> -txn.amount
                }
            }
        val unabsorbedExcess = (excess - autoInTotalAtSet).coerceAtLeast(0.0)
        val minFloor = kotlin.math.min(0.0, targetAtSet)
        val result = (adjusted - unabsorbedExcess).coerceAtLeast(minFloor)
        return if (unabsorbedExcess > 0.005) {
            result.coerceAtMost(max(0.0, targetAtSet))
        } else {
            result
        }
    }

    fun buildDailyNetAssetSeries(
        year: Int,
        month: Int,
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        nowMillis: Long = System.currentTimeMillis(),
        cards: List<CreditCard> = emptyList(),
        cardTxns: List<CardTransaction> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): List<NetAssetPoint> {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)
        val curDay = nowCal.get(Calendar.DAY_OF_MONTH)

        val monthCal = Calendar.getInstance().apply { set(year, month, 1) }
        val maxDays = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lastDay = when {
            year > curYear || (year == curYear && month > curMonth) -> 0
            year == curYear && month == curMonth -> curDay
            else -> maxDays
        }
        if (lastDay <= 0) return emptyList()

        return (1..lastDay).map { day ->
            val cutoff = if (year == curYear && month == curMonth && day == curDay) {
                nowMillis
            } else {
                endOfDay(year, month, day)
            }
            val (net, assets, liabilities) = computeNetAssetAt(
                cutoff, accounts, txnsByAccount, bills, nowMillis, cards, cardTxns, cardPayments
            )
            val date = Calendar.getInstance().apply { set(year, month, day) }.time
            NetAssetPoint(
                label = day.toString(),
                fullLabel = dayMonthFmt.format(date),
                netAsset = net,
                assets = assets,
                liabilities = liabilities,
                periodEndMillis = cutoff
            )
        }
    }

    fun buildMonthlyNetAssetSeries(
        year: Int,
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        nowMillis: Long = System.currentTimeMillis(),
        cards: List<CreditCard> = emptyList(),
        cardTxns: List<CardTransaction> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): List<NetAssetPoint> {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)

        val lastMonth = when {
            year > curYear -> -1
            year == curYear -> curMonth
            else -> Calendar.DECEMBER
        }
        if (lastMonth < 0) return emptyList()

        return (0..lastMonth).map { m ->
            val cutoff = if (year == curYear && m == curMonth) nowMillis else endOfMonth(year, m)
            val (net, assets, liabilities) = computeNetAssetAt(
                cutoff, accounts, txnsByAccount, bills, nowMillis, cards, cardTxns, cardPayments
            )
            val date = Calendar.getInstance().apply { set(year, m, 1) }.time
            NetAssetPoint(
                label = shortMonthFmt.format(date),
                fullLabel = monthYearFmt.format(date),
                netAsset = net,
                assets = assets,
                liabilities = liabilities,
                periodEndMillis = cutoff
            )
        }
    }

    fun buildYearlyNetAssetSeries(
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        nowMillis: Long = System.currentTimeMillis(),
        cards: List<CreditCard> = emptyList(),
        cardTxns: List<CardTransaction> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): List<NetAssetPoint> {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curYear = nowCal.get(Calendar.YEAR)

        val earliestMillis = listOfNotNull(
            txnsByAccount.values.flatten().minOfOrNull { it.occurredAt },
            cardTxns.minOfOrNull { it.occurredAt },
            bills.minOfOrNull { it.detectedAt }
        ).minOrNull() ?: nowMillis
        val firstYear = Calendar.getInstance().apply { timeInMillis = earliestMillis }.get(Calendar.YEAR)
            .coerceAtMost(curYear)

        return (firstYear..curYear).map { y ->
            val cutoff = if (y == curYear) nowMillis else endOfYear(y)
            val (net, assets, liabilities) = computeNetAssetAt(
                cutoff, accounts, txnsByAccount, bills, nowMillis, cards, cardTxns, cardPayments
            )
            NetAssetPoint(
                label = y.toString(),
                fullLabel = "Year $y",
                netAsset = net,
                assets = assets,
                liabilities = liabilities,
                periodEndMillis = cutoff
            )
        }
    }

    fun buildCashflowSeries(
        granularity: ChartGranularity,
        year: Int,
        month: Int,
        accounts: List<Account>,
        allAccountTxns: List<AccountTransaction>,
        allCardTxns: List<CardTransaction>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<CashflowPoint> {
        val countedIds = accounts.filter { !it.isArchived && it.includeInTotal }.map { it.id }.toSet()
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)
        val curDay = nowCal.get(Calendar.DAY_OF_MONTH)

        return when (granularity) {
            ChartGranularity.DAILY -> {
                val maxDays = Calendar.getInstance().apply { set(year, month, 1) }
                    .getActualMaximum(Calendar.DAY_OF_MONTH)
                val lastDay = when {
                    year > curYear || (year == curYear && month > curMonth) -> 0
                    year == curYear && month == curMonth -> curDay
                    else -> maxDays
                }
                (1..lastDay).map { day ->
                    val s = startOfDay(year, month, day)
                    val e = endOfDay(year, month, day)
                    val (inc, exp) = computeCashflowTotals(s, e, countedIds, allAccountTxns, allCardTxns)
                    val d = Calendar.getInstance().apply { set(year, month, day) }.time
                    CashflowPoint(
                        label = day.toString(),
                        fullLabel = dayMonthFmt.format(d),
                        income = inc,
                        expense = exp
                    )
                }
            }
            ChartGranularity.MONTHLY -> {
                val lastMonth = when {
                    year > curYear -> -1
                    year == curYear -> curMonth
                    else -> Calendar.DECEMBER
                }
                (0..lastMonth).map { m ->
                    val s = startOfMonth(year, m)
                    val e = endOfMonth(year, m)
                    val (inc, exp) = computeCashflowTotals(s, e, countedIds, allAccountTxns, allCardTxns)
                    val d = Calendar.getInstance().apply { set(year, m, 1) }.time
                    CashflowPoint(
                        label = shortMonthFmt.format(d),
                        fullLabel = monthYearFmt.format(d),
                        income = inc,
                        expense = exp
                    )
                }
            }
            ChartGranularity.YEARLY -> {
                val earliestMillis = listOfNotNull(
                    allAccountTxns.minOfOrNull { it.occurredAt },
                    allCardTxns.minOfOrNull { it.occurredAt }
                ).minOrNull() ?: nowMillis
                val firstYear = Calendar.getInstance().apply { timeInMillis = earliestMillis }
                    .get(Calendar.YEAR).coerceAtMost(curYear)
                (firstYear..curYear).map { y ->
                    val s = startOfYear(y)
                    val e = endOfYear(y)
                    val (inc, exp) = computeCashflowTotals(s, e, countedIds, allAccountTxns, allCardTxns)
                    CashflowPoint(
                        label = y.toString(),
                        fullLabel = "Year $y",
                        income = inc,
                        expense = exp
                    )
                }
            }
        }
    }

    fun computeCashflowTotals(
        startMillis: Long,
        endMillis: Long,
        countedAccountIds: Set<Long>,
        allAccountTxns: List<AccountTransaction>,
        allCardTxns: List<CardTransaction>
    ): Pair<Double, Double> {
        var income = 0.0
        var expense = 0.0
        for (txn in allAccountTxns) {
            if (txn.occurredAt < startMillis || txn.occurredAt > endMillis) continue
            if (txn.category == TxnCategory.DIRECT_SET) continue
            if (txn.accountId in countedAccountIds &&
                TxnCategory.countsAsIncome(txn.isExpenseOverride, txn.direction)
            ) {
                income += txn.amount
            }
            if ((txn.transferId == null || txn.isExpenseOverride == AccountTransaction.EXPENSE_INCLUDED) &&
                TxnCategory.countsAsExpense(txn.isExpenseOverride, txn.direction, txn.category)
            ) {
                expense += txn.amount
            }
        }
        for (ct in allCardTxns) {
            if (ct.occurredAt in startMillis..endMillis) {
                expense += ct.amount
            }
        }
        return income to expense
    }

    fun buildCategoryBreakdown(
        startMillis: Long,
        endMillis: Long,
        prevStartMillis: Long,
        prevEndMillis: Long,
        countedAccountIds: Set<Long>,
        allAccountTxns: List<AccountTransaction>,
        allCardTxns: List<CardTransaction>,
        showIncome: Boolean
    ): List<CategoryChartSlice> {
        val currentMap = categoryMapForRange(startMillis, endMillis, countedAccountIds, allAccountTxns, allCardTxns, showIncome)
        val prevMap = categoryMapForRange(prevStartMillis, prevEndMillis, countedAccountIds, allAccountTxns, allCardTxns, showIncome)
        val total = currentMap.values.sum()
        if (total <= 0.0) return emptyList()

        return currentMap.entries
            .sortedByDescending { it.value }
            .map { (cat, amt) ->
                CategoryChartSlice(
                    category = cat,
                    amount = amt,
                    prevAmount = prevMap[cat] ?: 0.0,
                    percent = (amt / total).toFloat()
                )
            }
    }

    private fun categoryMapForRange(
        startMillis: Long,
        endMillis: Long,
        countedAccountIds: Set<Long>,
        allAccountTxns: List<AccountTransaction>,
        allCardTxns: List<CardTransaction>,
        showIncome: Boolean
    ): Map<String, Double> {
        val map = LinkedHashMap<String, Double>()
        for (txn in allAccountTxns) {
            if (txn.occurredAt < startMillis || txn.occurredAt > endMillis) continue
            if (txn.category == TxnCategory.DIRECT_SET) continue
            if (showIncome) {
                if (txn.accountId in countedAccountIds &&
                    TxnCategory.countsAsIncome(txn.isExpenseOverride, txn.direction)
                ) {
                    val cat = normalizeCategory(txn.category, fallback = "Income")
                    map[cat] = (map[cat] ?: 0.0) + txn.amount
                }
            } else {
                if ((txn.transferId == null || txn.isExpenseOverride == AccountTransaction.EXPENSE_INCLUDED) &&
                    TxnCategory.countsAsExpense(txn.isExpenseOverride, txn.direction, txn.category)
                ) {
                    val cat = normalizeCategory(txn.category, fallback = "Expense")
                    map[cat] = (map[cat] ?: 0.0) + txn.amount
                }
            }
        }
        if (!showIncome) {
            for (ct in allCardTxns) {
                if (ct.occurredAt in startMillis..endMillis) {
                    val cat = normalizeCategory(ct.category, fallback = "Card")
                    map[cat] = (map[cat] ?: 0.0) + ct.amount
                }
            }
        }
        return map
    }

    private fun normalizeCategory(raw: String?, fallback: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return fallback
        return if (trimmed.startsWith("Fund Transfer", ignoreCase = true) ||
            trimmed.startsWith("Fund-Transfer", ignoreCase = true)
        ) {
            "Fund Transfer"
        } else {
            trimmed
        }
    }

    fun buildAllocationBreakdown(
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        accountTypes: List<AccountTypeConfig>,
        cards: List<CreditCard> = emptyList(),
        cardTxns: List<CardTransaction> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): List<AllocationSlice> {
        val typeConfigMap = accountTypes.associateBy { it.name.trim().lowercase() }
        val grouped = accounts.filter { !it.isArchived && it.includeInTotal }
            .groupBy { it.effectiveType.trim().lowercase() }

        val slices = mutableListOf<AllocationSlice>()
        for ((key, list) in grouped) {
            val typeName = typeConfigMap[key]?.name ?: list.first().effectiveType
            val isLiab = typeConfigMap[key]?.isLiability ?: list.all { it.isLiability }
            val sum = list.sumOf { acct ->
                val txns = txnsByAccount[acct.id].orEmpty()
                val bal = if (acct.kind == Account.KIND_CASH) {
                    AccountRepository.effectiveBalance(txns)
                } else {
                    AccountRepository.computeAccountBalance(txns)
                }
                if (isLiab) abs(bal) else max(0.0, bal)
            }
            if (sum > 0.0) {
                slices.add(AllocationSlice(label = typeName, amount = sum, isLiability = isLiab))
            }
        }
        val cardIds = cards.map { it.id }.toSet()
        val billsByCard = bills.groupBy { it.cardId }
        val knownCardLiability = cards.sumOf { card ->
            CardRepository.computeCardOutstanding(
                card = card,
                cardBills = billsByCard[card.id].orEmpty(),
                allCardTxns = cardTxns,
                allPayments = cardPayments
            )
        }
        val orphanCardLiability = bills.filter { it.cardId !in cardIds && !it.isPaid && !it.isSuperseded }
            .sumOf { max(0.0, it.totalDue - it.paidAmount) }
        val cardLiability = knownCardLiability + orphanCardLiability
        if (cardLiability > 0.0) {
            slices.add(AllocationSlice(label = "Credit Cards", amount = cardLiability, isLiability = true))
        }
        return slices.sortedWith(compareBy<AllocationSlice> { it.isLiability }.thenByDescending { it.amount })
    }

    fun buildPeriodSnapshot(
        isYearly: Boolean,
        year: Int,
        month: Int,
        accounts: List<Account>,
        txnsByAccount: Map<Long, List<AccountTransaction>>,
        bills: List<BillWithCard>,
        allAccountTxns: List<AccountTransaction>,
        allCardTxns: List<CardTransaction>,
        nowMillis: Long = System.currentTimeMillis(),
        cards: List<CreditCard> = emptyList(),
        cardPayments: List<CardPayment> = emptyList()
    ): PeriodComparisonSnapshot {
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)

        val start = if (isYearly) startOfYear(year) else startOfMonth(year, month)
        val rawEnd = if (isYearly) endOfYear(year) else endOfMonth(year, month)
        val isCurrentPeriod = if (isYearly) year == curYear else (year == curYear && month == curMonth)
        val cutoff = if (isCurrentPeriod) nowMillis else rawEnd

        val label = if (isYearly) {
            year.toString()
        } else {
            monthYearFmt.format(Calendar.getInstance().apply { set(year, month, 1) }.time)
        }

        val countedIds = accounts.filter { !it.isArchived && it.includeInTotal }.map { it.id }.toSet()
        val (netAsset, assets, liabilities) = computeNetAssetAt(
            cutoff, accounts, txnsByAccount, bills, nowMillis, cards, allCardTxns, cardPayments
        )
        val (income, expense) = computeCashflowTotals(start, rawEnd, countedIds, allAccountTxns, allCardTxns)
        val topCats = categoryMapForRange(start, rawEnd, countedIds, allAccountTxns, allCardTxns, showIncome = false)
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        return PeriodComparisonSnapshot(
            label = label,
            netAsset = netAsset,
            assets = assets,
            liabilities = liabilities,
            income = income,
            expense = expense,
            netCashflow = income - expense,
            topCategories = topCats
        )
    }
}
