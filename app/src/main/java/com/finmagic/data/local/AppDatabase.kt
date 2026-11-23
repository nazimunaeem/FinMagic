package com.finmagic.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.dao.ActivityLogDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.TransferDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.ActivityLog
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.Transfer
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.local.entity.SmsRuleApplication

@Database(
    entities = [
        CreditCard::class, Bill::class, ActivityLog::class, SmsRecord::class, CardPayment::class,
        Account::class, AccountTransaction::class, Transfer::class, CardTransaction::class,
        SmsTeachingRule::class, SmsRuleApplication::class
    ],
    version = 16,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun creditCardDao(): CreditCardDao
    abstract fun billDao(): BillDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun smsRecordDao(): SmsRecordDao
    abstract fun cardPaymentDao(): CardPaymentDao
    abstract fun accountDao(): AccountDao
    abstract fun accountTransactionDao(): AccountTransactionDao
    abstract fun transferDao(): TransferDao
    abstract fun cardTransactionDao(): CardTransactionDao
    abstract fun smsTeachingRuleDao(): com.finmagic.data.local.dao.SmsTeachingRuleDao
    abstract fun smsRuleApplicationDao(): com.finmagic.data.local.dao.SmsRuleApplicationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN isSuperseded INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN paidViaSms INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_payments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardTail` TEXT NOT NULL, " +
                        "`amountBdt` REAL NOT NULL, " +
                        "`amountUsd` REAL NOT NULL, " +
                        "`paidAt` INTEGER NOT NULL, " +
                        "`smsBody` TEXT NOT NULL, " +
                        "`appliedBillId` INTEGER, " +
                        "`detectedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_payments_smsBody` ON `card_payments` (`smsBody`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_payments_appliedBillId` ON `card_payments` (`appliedBillId`)")
            }
        }

        // Accounts core (plan §2.2): Account, AccountTransaction, Transfer.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accounts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`bankName` TEXT NOT NULL DEFAULT '', " +
                        "`accountMask` TEXT NOT NULL, " +
                        "`currency` TEXT NOT NULL DEFAULT 'BDT', " +
                        "`isArchived` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_bankName_accountMask` " +
                        "ON `accounts` (`bankName`, `accountMask`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `account_transactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "`currency` TEXT NOT NULL DEFAULT 'BDT', " +
                        "`balanceAfter` REAL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`category` TEXT, " +
                        "`counterparty` TEXT, " +
                        "`smsRecordId` INTEGER, " +
                        "`transferId` INTEGER, " +
                        "`smsBody` TEXT, " +
                        "`isManual` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transactions_accountId` ON `account_transactions` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transactions_occurredAt` ON `account_transactions` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transactions_smsBody` ON `account_transactions` (`smsBody`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_transactions_transferId` ON `account_transactions` (`transferId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transfers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fromAccountId` INTEGER NOT NULL, " +
                        "`toAccountId` INTEGER NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_fromAccountId` ON `transfers` (`fromAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_toAccountId` ON `transfers` (`toAccountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_occurredAt` ON `transfers` (`occurredAt`)")
            }
        }

        // Wallet identity rework: wallets are SIM-owned (SIM1/SIM2, number as mask),
        // counterparty wallets from funding SMS must not exist. Drop all wallet rows
        // plus legacy "Bank"-named accounts; the next scan rebuilds them correctly
        // (transactions cascade-delete so body dedup re-admits their SMS).
        // BBKSRSL is now displayed without the "Savings A/C" suffix.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE accounts SET name = 'BBKSRSL' WHERE bankName = 'BBKSRSL Savings'")
                db.execSQL("DELETE FROM accounts WHERE kind = 'WALLET'")
                db.execSQL("DELETE FROM accounts WHERE bankName = 'Bank'")
            }
        }

        // Expenses tab: persist card POS purchases, and let any account transaction be
        // force-included in expenses via an override flag.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_transactions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardTail` TEXT NOT NULL, " +
                        "`bankName` TEXT NOT NULL, " +
                        "`merchant` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`balanceAfter` REAL, " +
                        "`category` TEXT, " +
                        "`smsBody` TEXT NOT NULL, " +
                        "`detectedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_transactions_smsBody` ON `card_transactions` (`smsBody`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_transactions_occurredAt` ON `card_transactions` (`occurredAt`)")
                db.execSQL("ALTER TABLE account_transactions ADD COLUMN isExpenseOverride INTEGER NOT NULL DEFAULT 0")
            }
        }

        // isExpenseOverride widened in Kotlin from Boolean (0/1) to a tri-state Int
        // (-1 exclude / 0 auto / 1 include). The stored column is unchanged
        // (INTEGER NOT NULL DEFAULT 0), so existing 0/1 values keep their meaning;
        // only Room's identity hash changes, hence the no-op migration.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        // Per-account "include in total" toggle (checkbox in account edit dialogs).
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN includeInTotal INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sms_teaching_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `senderKey` TEXT, `requiredKeywords` TEXT NOT NULL, `excludedKeywords` TEXT NOT NULL, `action` TEXT NOT NULL, `accountId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_teaching_rules_senderKey` ON `sms_teaching_rules` (`senderKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_teaching_rules_enabled` ON `sms_teaching_rules` (`enabled`)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Extend sms_teaching_rules with new columns from the teaching plan.
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN matchCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN lastMatchedAt INTEGER")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_teaching_rules_createdAt` ON `sms_teaching_rules` (`createdAt`)")

                // Rule-provenance audit table (plan §5).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sms_rule_applications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ruleId` INTEGER NOT NULL, " +
                        "`smsRecordId` INTEGER NOT NULL, " +
                        "`transactionId` INTEGER, " +
                        "`appliedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_rule_applications_ruleId` ON `sms_rule_applications` (`ruleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sms_rule_applications_smsRecordId` ON `sms_rule_applications` (`smsRecordId`)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE credit_cards ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE credit_cards ADD COLUMN creditLimitCurrency TEXT NOT NULL DEFAULT 'BDT'")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN accountType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE accounts ADD COLUMN isLiability INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_teaching_rules ADD COLUMN cardId INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cardbill.db"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { INSTANCE = it }
            }
        }
    }
}
