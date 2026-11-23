package com.finmagic.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.finmagic.data.local.AppDatabase
import com.finmagic.data.local.BackupPreferencesPayload
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.SmsRuleApplicationDao
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.dao.TransferDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsRuleApplication
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.local.entity.Transfer
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.CardRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

data class AccountBalanceSnapshot(
    val id: Long,
    val name: String,
    val kind: String,
    val bankName: String,
    val accountMask: String,
    val accountType: String,
    val isLiability: Boolean,
    val includeInTotal: Boolean,
    val currency: String,
    val balance: Double
)

data class CardOutstandingSnapshot(
    val id: Long,
    val displayName: String,
    val bankName: String,
    val cardLast4: String,
    val creditLimit: Double,
    val currency: String,
    val outstanding: Double,
    val unpaidBillDue: Double,
    val outstandingAnchor: Double?,
    val outstandingAnchorAt: Long
)

data class BackupBalancesSnapshot(
    val netAsset: Double = 0.0,
    val assetsTotal: Double = 0.0,
    val accountLiabilitiesTotal: Double = 0.0,
    val cardOutstandingTotal: Double = 0.0,
    val accounts: List<AccountBalanceSnapshot> = emptyList(),
    val cards: List<CardOutstandingSnapshot> = emptyList()
)

data class BackupDatabasePayload(
    val accounts: List<Account> = emptyList(),
    val accountTransactions: List<AccountTransaction> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val creditCards: List<CreditCard> = emptyList(),
    val cardTransactions: List<CardTransaction> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val cardPayments: List<CardPayment> = emptyList(),
    val smsTeachingRules: List<SmsTeachingRule> = emptyList(),
    val smsRuleApplications: List<SmsRuleApplication> = emptyList(),
    val smsRecords: List<SmsRecord> = emptyList()
)

data class FinMagicBackup(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val appVersion: String = "2.0.1",
    val createdAt: Long = System.currentTimeMillis(),
    val balancesSnapshot: BackupBalancesSnapshot = BackupBalancesSnapshot(),
    val database: BackupDatabasePayload = BackupDatabasePayload(),
    val preferences: BackupPreferencesPayload = BackupPreferencesPayload()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BackupPreviewSummary(
    val createdAt: Long,
    val appVersion: String,
    val netAsset: Double,
    val accountCount: Int,
    val transactionCount: Int,
    val cardCount: Int,
    val billCount: Int,
    val ruleCount: Int,
    val accountBalances: List<AccountBalanceSnapshot>,
    val cardOutstandings: List<CardOutstandingSnapshot>
)

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val accountDao: AccountDao,
    private val accountTransactionDao: AccountTransactionDao,
    private val transferDao: TransferDao,
    private val creditCardDao: CreditCardDao,
    private val cardTransactionDao: CardTransactionDao,
    private val billDao: BillDao,
    private val cardPaymentDao: CardPaymentDao,
    private val smsTeachingRuleDao: SmsTeachingRuleDao,
    private val smsRuleApplicationDao: SmsRuleApplicationDao,
    private val smsRecordDao: SmsRecordDao,
    private val preferenceManager: PreferenceManager
) {
    companion object {
        const val PERSISTENT_FILE_NAME = "FinMagic_Backup.json"
        const val PERSISTENT_RELATIVE_DIR = "Download/FinMagic"

        private val lazyDelegateExclusion = object : com.google.gson.ExclusionStrategy {
            override fun shouldSkipField(f: com.google.gson.FieldAttributes): Boolean =
                f.name.endsWith("\$delegate") || kotlin.Lazy::class.java.isAssignableFrom(f.declaredClass)

            override fun shouldSkipClass(clazz: Class<*>): Boolean =
                kotlin.Lazy::class.java.isAssignableFrom(clazz)
        }

        private val gson = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .setExclusionStrategies(lazyDelegateExclusion)
            .create()

        fun serializeBackup(backup: FinMagicBackup): String = gson.toJson(backup)

        fun parseBackup(json: String): FinMagicBackup {
            if (json.isBlank()) {
                throw IllegalArgumentException("Backup file is empty.")
            }
            val parsed = runCatching { gson.fromJson(json, FinMagicBackup::class.java) }
                .getOrElse { throw IllegalArgumentException("Invalid JSON backup format.", it) }
                ?: throw IllegalArgumentException("Invalid JSON backup format.")
            if (parsed.schemaVersion < 1) {
                throw IllegalArgumentException("Unsupported backup schema version: ${parsed.schemaVersion}")
            }
            if (parsed.database == null || parsed.balancesSnapshot == null) {
                throw IllegalArgumentException("Backup file is missing required database sections.")
            }
            return parsed
        }

        fun summarizeBackup(backup: FinMagicBackup): BackupPreviewSummary = BackupPreviewSummary(
            createdAt = backup.createdAt,
            appVersion = backup.appVersion.ifBlank { "Unknown" },
            netAsset = backup.balancesSnapshot.netAsset,
            accountCount = backup.database.accounts.size,
            transactionCount = backup.database.accountTransactions.size + backup.database.cardTransactions.size,
            cardCount = backup.database.creditCards.size,
            billCount = backup.database.bills.size,
            ruleCount = backup.database.smsTeachingRules.size,
            accountBalances = backup.balancesSnapshot.accounts,
            cardOutstandings = backup.balancesSnapshot.cards
        )
    }

    fun observeAutoBackupTriggers(): Flow<Unit> {
        val partA = combine(
            accountDao.observeAll(),
            accountTransactionDao.observeAllIncludingDirectSet(),
            creditCardDao.getAllCards(),
            billDao.getAllBillsWithCards()
        ) { accts, txns, cards, bills ->
            val hasMeaningfulData = accts.any { it.kind != Account.KIND_CASH } ||
                txns.isNotEmpty() || cards.isNotEmpty() || bills.isNotEmpty()
            hasMeaningfulData
        }
        val partB = combine(
            cardTransactionDao.observeAll(),
            cardPaymentDao.observeAllPayments(),
            smsTeachingRuleDao.observeAll()
        ) { cardTxns, payments, rules ->
            cardTxns.isNotEmpty() || payments.isNotEmpty() || rules.isNotEmpty()
        }
        return combine(partA, partB) { a, b -> a || b }
            .combine(preferenceManager.persistentBackupEnabled) { hasData, enabled ->
                hasData && enabled
            }
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { shouldWrite ->
                        if (shouldWrite) emit(Unit)
                    }
                }
            }
    }

    suspend fun hasMeaningfulData(): Boolean = withContext(Dispatchers.IO) {
        val accounts = accountDao.getAllIncludingArchivedOnce()
        val txns = accountTransactionDao.getAllOnce()
        val cards = creditCardDao.getAllCardsOnce()
        val bills = billDao.getAllBillsOnce()
        accounts.any { it.kind != Account.KIND_CASH } ||
            txns.isNotEmpty() || cards.isNotEmpty() || bills.isNotEmpty()
    }

    suspend fun buildBackup(appVersion: String = resolveAppVersion()): FinMagicBackup =
        withContext(Dispatchers.IO) {
            val accounts = accountDao.getAllIncludingArchivedOnce()
            val txns = accountTransactionDao.getAllOnce()
            val transfers = transferDao.getAllOnce()
            val cards = creditCardDao.getAllCardsOnce()
            val cardTxns = cardTransactionDao.getAllOnce()
            val bills = billDao.getAllBillsOnce()
            val cardPayments = cardPaymentDao.getAllPaymentsOnce()
            val rules = smsTeachingRuleDao.getAllOnce()
            val applications = smsRuleApplicationDao.getAllOnce()
            val smsRecords = smsRecordDao.getAllOnce()
            val prefs = preferenceManager.exportSnapshot()

            val txnsByAccount = txns.groupBy { it.accountId }
            val activeAccounts = accounts.filter { !it.isArchived }
            val accountSnapshots = activeAccounts.map { acct ->
                val acctTxns = txnsByAccount[acct.id].orEmpty()
                val bal = if (acct.kind == Account.KIND_CASH) {
                    AccountRepository.effectiveBalance(acctTxns)
                } else {
                    AccountRepository.computeAccountBalance(acctTxns)
                }
                AccountBalanceSnapshot(
                    id = acct.id,
                    name = acct.displayName,
                    kind = acct.kind,
                    bankName = acct.bankName,
                    accountMask = acct.accountMask,
                    accountType = acct.effectiveType,
                    isLiability = acct.isLiability,
                    includeInTotal = acct.includeInTotal,
                    currency = acct.currency,
                    balance = bal
                )
            }

            val cardMap = cards.associateBy { it.id }
            val billsWithCard = bills.mapNotNull { b ->
                val card = cardMap[b.cardId] ?: return@mapNotNull null
                BillWithCard(
                    billId = b.id,
                    cardId = b.cardId,
                    bankName = card.bankName,
                    cardLast4 = card.cardLast4,
                    cardNickname = card.cardNickname,
                    billPeriod = b.billPeriod,
                    totalDue = b.totalDue,
                    minDue = b.minDue,
                    dueDate = b.dueDate,
                    isPaid = b.isPaid,
                    isSuperseded = b.isSuperseded,
                    paidAmount = b.paidAmount,
                    paidAt = b.paidAt,
                    paidViaSms = b.paidViaSms,
                    smsBody = b.smsBody,
                    currency = b.currency,
                    detectedAt = b.detectedAt
                )
            }
            val billsByCard = billsWithCard.groupBy { it.cardId }

            val cardSnapshots = cards.map { card ->
                val cardBills = billsByCard[card.id].orEmpty()
                val unpaidBillDue = cardBills.filter { !it.isPaid && !it.isSuperseded }
                    .sumOf { max(0.0, it.totalDue - it.paidAmount) }
                val outstanding = CardRepository.computeCardOutstanding(
                    card = card,
                    cardBills = cardBills,
                    allCardTxns = cardTxns,
                    allPayments = cardPayments
                )
                CardOutstandingSnapshot(
                    id = card.id,
                    displayName = card.displayName,
                    bankName = card.bankName,
                    cardLast4 = card.cardLast4,
                    creditLimit = card.creditLimit,
                    currency = card.creditLimitCurrency,
                    outstanding = outstanding,
                    unpaidBillDue = unpaidBillDue,
                    outstandingAnchor = card.outstandingAnchor,
                    outstandingAnchorAt = card.outstandingAnchorAt
                )
            }

            val assetsTotal = accountSnapshots
                .filter { it.includeInTotal && !it.isLiability }
                .sumOf { it.balance }
            val accountLiabilitiesTotal = accountSnapshots
                .filter { it.includeInTotal && it.isLiability }
                .sumOf { abs(it.balance) }
            val cardOutstandingTotal = cardSnapshots.sumOf { it.outstanding }
            val netAsset = assetsTotal - accountLiabilitiesTotal - cardOutstandingTotal

            FinMagicBackup(
                schemaVersion = FinMagicBackup.CURRENT_SCHEMA_VERSION,
                appVersion = appVersion,
                createdAt = System.currentTimeMillis(),
                balancesSnapshot = BackupBalancesSnapshot(
                    netAsset = netAsset,
                    assetsTotal = assetsTotal,
                    accountLiabilitiesTotal = accountLiabilitiesTotal,
                    cardOutstandingTotal = cardOutstandingTotal,
                    accounts = accountSnapshots,
                    cards = cardSnapshots
                ),
                database = BackupDatabasePayload(
                    accounts = accounts,
                    accountTransactions = txns,
                    transfers = transfers,
                    creditCards = cards,
                    cardTransactions = cardTxns,
                    bills = bills,
                    cardPayments = cardPayments,
                    smsTeachingRules = rules,
                    smsRuleApplications = applications,
                    smsRecords = smsRecords
                ),
                preferences = prefs
            )
        }

    suspend fun writeToPersistentFile(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = serializeBackup(buildBackup())
            val labels = mutableListOf<String>()

            val safUriStr = preferenceManager.persistentBackupUri.first()
            if (!safUriStr.isNullOrBlank()) {
                val uri = Uri.parse(safUriStr)
                writeTextToUri(uri, json)
                labels.add(resolveDisplayName(uri) ?: "Custom Backup File")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStoreDownloads(json)
                labels.add("$PERSISTENT_RELATIVE_DIR/$PERSISTENT_FILE_NAME")
            }

            if (labels.isEmpty()) {
                error("Choose a backup file location below to save outside app storage on this Android version.")
            }

            val label = labels.joinToString(" & ")
            preferenceManager.recordBackupSuccess(System.currentTimeMillis(), label)
            label
        }
    }

    suspend fun writeBackupToUri(uri: Uri, persistAsTarget: Boolean = true): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = serializeBackup(buildBackup())
                if (persistAsTarget) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    preferenceManager.setPersistentBackupUri(uri.toString())
                }
                writeTextToUri(uri, json)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    preferenceManager.persistentBackupEnabled.first()
                ) {
                    runCatching { writeToMediaStoreDownloads(json) }
                }
                val label = resolveDisplayName(uri) ?: uri.lastPathSegment ?: PERSISTENT_FILE_NAME
                preferenceManager.recordBackupSuccess(System.currentTimeMillis(), label)
                label
            }
        }

    suspend fun previewBackupFromUri(uri: Uri): Result<BackupPreviewSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = readTextFromUri(uri)
                summarizeBackup(parseBackup(json))
            }
        }

    suspend fun restoreFromUri(uri: Uri): Result<BackupPreviewSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = readTextFromUri(uri)
                restoreFromJson(json).getOrThrow()
            }
        }

    suspend fun restoreFromJson(json: String): Result<BackupPreviewSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val backup = parseBackup(json)
                val db = backup.database
                database.withTransaction {
                    // Delete child tables before parent tables
                    billDao.clearAll()
                    accountTransactionDao.clearAll()
                    transferDao.clearAll()
                    cardTransactionDao.clearAll()
                    cardPaymentDao.clearAll()
                    smsRuleApplicationDao.clearAll()
                    smsTeachingRuleDao.clearAll()
                    smsRecordDao.clearAll()
                    creditCardDao.clearAll()
                    accountDao.clearAll()

                    // Re-insert parents before children
                    if (db.accounts.isNotEmpty()) accountDao.insertAllReplace(db.accounts)
                    if (db.creditCards.isNotEmpty()) creditCardDao.insertAllReplace(db.creditCards)
                    if (db.smsRecords.isNotEmpty()) smsRecordDao.insertAllReplace(db.smsRecords)
                    if (db.transfers.isNotEmpty()) transferDao.insertAllReplace(db.transfers)
                    if (db.accountTransactions.isNotEmpty()) {
                        accountTransactionDao.insertAllReplace(db.accountTransactions)
                    }
                    if (db.cardTransactions.isNotEmpty()) {
                        cardTransactionDao.insertAllReplace(db.cardTransactions)
                    }
                    if (db.bills.isNotEmpty()) billDao.insertAllReplace(db.bills)
                    if (db.cardPayments.isNotEmpty()) cardPaymentDao.insertAllReplace(db.cardPayments)
                    if (db.smsTeachingRules.isNotEmpty()) {
                        smsTeachingRuleDao.insertAllReplace(db.smsTeachingRules)
                    }
                    if (db.smsRuleApplications.isNotEmpty()) {
                        smsRuleApplicationDao.insertAllReplace(db.smsRuleApplications)
                    }
                }
                preferenceManager.restoreFromBackup(backup.preferences)
                summarizeBackup(backup)
            }
        }

    private fun writeTextToUri(uri: Uri, content: String) {
        val resolver = context.contentResolver
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: error("Unable to open backup destination for writing.")
        out.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Unable to open selected backup file.")
        return input.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStoreDownloads(json: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val targetRelPath = "${Environment.DIRECTORY_DOWNLOADS}/FinMagic"

        val existingUri: Uri? = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(PERSISTENT_FILE_NAME, "$targetRelPath%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                ContentUris.withAppendedId(collection, id)
            } else {
                null
            }
        }

        if (existingUri != null) {
            val updated = runCatching { writeTextToUri(existingUri, json) }.isSuccess
            if (updated) return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, PERSISTENT_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val insertedUri = resolver.insert(collection, values)
            ?: error("Could not create backup file in Downloads.")
        try {
            writeTextToUri(insertedUri, json)
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(insertedUri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(insertedUri, null, null) }
            throw t
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun resolveAppVersion(): String {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.0.1"
        }.getOrDefault("2.0.1")
    }
}
