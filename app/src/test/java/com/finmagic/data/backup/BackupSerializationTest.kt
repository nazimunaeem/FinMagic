package com.finmagic.data.backup

import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.local.BackupPreferencesPayload
import com.finmagic.data.local.RecurringTemplate
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.SmsTeachingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializationTest {

    @Test
    fun serializeParseAndSummarize_roundTripsAllEntitiesAndSnapshot() {
        val original = FinMagicBackup(
            schemaVersion = FinMagicBackup.CURRENT_SCHEMA_VERSION,
            appVersion = "2.0.1",
            createdAt = 1_774_000_000_000L,
            balancesSnapshot = BackupBalancesSnapshot(
                netAsset = 120_000.0,
                assetsTotal = 150_000.0,
                accountLiabilitiesTotal = 10_000.0,
                cardOutstandingTotal = 20_000.0,
                accounts = listOf(
                    AccountBalanceSnapshot(
                        id = 1L,
                        name = "City Savings",
                        kind = Account.KIND_BANK,
                        bankName = "City Bank",
                        accountMask = "9988",
                        accountType = "Savings",
                        isLiability = false,
                        includeInTotal = true,
                        currency = "BDT",
                        balance = 150_000.0
                    )
                ),
                cards = listOf(
                    CardOutstandingSnapshot(
                        id = 2L,
                        displayName = "City Platinum",
                        bankName = "City Bank",
                        cardLast4 = "4321",
                        creditLimit = 300_000.0,
                        currency = "BDT",
                        outstanding = 20_000.0,
                        unpaidBillDue = 12_000.0,
                        outstandingAnchor = 20_000.0,
                        outstandingAnchorAt = 1_774_000_000_000L
                    )
                )
            ),
            database = BackupDatabasePayload(
                accounts = listOf(
                    Account(
                        id = 1L,
                        name = "City Savings",
                        kind = Account.KIND_BANK,
                        bankName = "City Bank",
                        accountMask = "9988",
                        accountType = "Savings",
                        isLiability = false
                    ),
                    Account(
                        id = 2L,
                        name = "Car Loan",
                        kind = Account.KIND_BANK,
                        bankName = "City Bank",
                        accountMask = "7777",
                        accountType = "Loan",
                        isLiability = true
                    )
                ),
                accountTransactions = listOf(
                    AccountTransaction(
                        id = 10L,
                        accountId = 1L,
                        direction = AccountTransaction.DIRECTION_IN,
                        amount = 150_000.0,
                        occurredAt = 1_773_500_000_000L,
                        category = "Salary",
                        isManual = true,
                        isExpenseOverride = AccountTransaction.EXPENSE_INCLUDED
                    )
                ),
                creditCards = listOf(
                    CreditCard(
                        id = 2L,
                        bankName = "City Bank",
                        cardLast4 = "4321",
                        cardNickname = "City Platinum",
                        creditLimit = 300_000.0,
                        outstandingAnchor = 20_000.0,
                        outstandingAnchorAt = 1_774_000_000_000L
                    )
                ),
                bills = listOf(
                    Bill(
                        id = 5L,
                        cardId = 2L,
                        billPeriod = "Mar 2026",
                        totalDue = 12_000.0,
                        minDue = 1_000.0,
                        dueDate = "28/03/2026"
                    )
                ),
                smsTeachingRules = listOf(
                    SmsTeachingRule(
                        id = 8L,
                        name = "City Credit Rule",
                        senderKey = "CITYBANK",
                        requiredKeywords = """["credited"]""",
                        action = SmsTeachingRule.ACTION_CREDIT,
                        accountId = 1L
                    )
                )
            ),
            preferences = BackupPreferencesPayload(
                themeMode = AppThemeMode.DARK.name,
                categoryNicknames = mapOf("FOOD" to "Dining"),
                recurringTemplates = listOf(RecurringTemplate("EXPENSE", "Rent", 25_000.0, "Housing")),
                customAccountTypes = listOf(AccountTypeConfig("DPS", isLiability = false))
            )
        )

        val json = BackupManager.serializeBackup(original)
        assertTrue(json.contains("\"balancesSnapshot\""))
        assertTrue(json.contains("City Platinum"))

        val parsed = BackupManager.parseBackup(json)
        assertEquals(original.appVersion, parsed.appVersion)
        assertEquals(120_000.0, parsed.balancesSnapshot.netAsset, 0.01)
        assertEquals(20_000.0, parsed.database.creditCards.first().outstandingAnchor!!, 0.01)
        assertEquals(true, parsed.database.accounts[1].isLiability)
        assertEquals(
            AccountTransaction.EXPENSE_INCLUDED,
            parsed.database.accountTransactions.first().isExpenseOverride
        )
        assertEquals("Dining", parsed.preferences.categoryNicknames["FOOD"])

        val summary = BackupManager.summarizeBackup(parsed)
        assertEquals(2, summary.accountCount)
        assertEquals(1, summary.cardCount)
        assertEquals(1, summary.transactionCount)
        assertEquals(1, summary.billCount)
        assertEquals(1, summary.ruleCount)
        assertEquals(120_000.0, summary.netAsset, 0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseBackup_rejectsBlankInput() {
        BackupManager.parseBackup("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseBackup_rejectsMalformedJson() {
        BackupManager.parseBackup("{not valid json")
    }
}
