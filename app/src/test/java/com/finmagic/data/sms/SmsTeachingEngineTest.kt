package com.finmagic.data.sms

import com.finmagic.data.local.entity.SmsTeachingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for SmsTeachingEngine rule matching, scoring, and amount extraction.
 * Tests the static companion methods which are pure functions.
 */
class SmsTeachingEngineTest {

    // --- Amount extraction ---

    @Test
    fun extractAmount_tkPrefix() {
        val body = "Tk 1,250.00 debited from A/C ***1234"
        val amount = SmsTeachingEngine.extractAmount(body)
        assertEquals(1250.00, amount!!, 0.01)
    }

    @Test
    fun extractAmount_bdtPrefix() {
        val body = "BDT2,000.00 credited to account"
        val amount = SmsTeachingEngine.extractAmount(body)
        assertEquals(2000.00, amount!!, 0.01)
    }

    @Test
    fun extractAmount_tkDot() {
        val body = "Tk. 200.00 sent via Rocket"
        val amount = SmsTeachingEngine.extractAmount(body)
        assertEquals(200.00, amount!!, 0.01)
    }

    @Test
    fun extractAmount_noMatch() {
        val body = "Your OTP is 456789"
        assertNull(SmsTeachingEngine.extractAmount(body))
    }

    @Test
    fun extractAmount_zeroAmount() {
        val body = "Tk 0.00 balance update"
        val amount = SmsTeachingEngine.extractAmount(body)
        assertEquals(0.0, amount!!, 0.01)
    }

    @Test
    fun extractAmount_caseInsensitive() {
        val body = "tk 500.00 debited"
        val amount = SmsTeachingEngine.extractAmount(body)
        assertEquals(500.00, amount!!, 0.01)
    }

    // --- Text normalization ---

    @Test
    fun normalizeText_collapseWhitespace() {
        val result = SmsTeachingEngine.normalizeText("Hello   world\n\n\ttest")
        assertEquals("Hello world test", result)
    }

    @Test
    fun normalizeText_nonBreakingSpace() {
        val result = SmsTeachingEngine.normalizeText("Hello\u00A0world")
        assertEquals("Hello world", result)
    }

    @Test
    fun normalizeText_trims() {
        val result = SmsTeachingEngine.normalizeText("  hello  ")
        assertEquals("hello", result)
    }

    // --- Rule matching via companion.findBestMatch ---

    private fun makeRule(
        id: Long = 1,
        senderKey: String? = null,
        requiredKeywords: String = "",
        excludedKeywords: String = "",
        action: String = SmsTeachingRule.ACTION_DEBIT,
        accountId: Long = 1L,
        priority: Int = 0
    ) = SmsTeachingRule(
        id = id,
        name = "Test rule",
        senderKey = senderKey,
        requiredKeywords = requiredKeywords,
        excludedKeywords = excludedKeywords,
        action = action,
        accountId = accountId,
        priority = priority
    )

    private fun findMatch(
        rules: List<SmsTeachingRule>,
        sender: String?,
        body: String
    ): SmsTeachingRule? {
        return SmsTeachingEngine.findBestMatch(rules, sender, SmsTeachingEngine.normalizeText(body))
    }

    @Test
    fun findBestMatch_noRules_returnsNull() {
        val result = findMatch(emptyList(), "16216", "test body")
        assertNull(result)
    }

    @Test
    fun findBestMatch_exactSenderMatch() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = "debited")
        val result = findMatch(listOf(rule), "16216", "Tk 100 debited from account")
        assertNotNull(result)
        assertEquals(rule.id, result!!.id)
    }

    @Test
    fun findBestMatch_senderMismatch() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = "debited")
        val result = findMatch(listOf(rule), "99999", "Tk 100 debited from account")
        assertNull(result)
    }

    @Test
    fun findBestMatch_requiredKeywordMissing() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = "debited|credit")
        val result = findMatch(listOf(rule), "16216", "Tk 100 credit from account")
        assertNull(result)
    }

    @Test
    fun findBestMatch_excludedKeywordPresent() {
        val rule = makeRule(
            senderKey = "16216",
            requiredKeywords = "account",
            excludedKeywords = "OTP"
        )
        val result = findMatch(listOf(rule), "16216", "Your OTP is 123456 for account")
        assertNull(result)
    }

    @Test
    fun findBestMatch_higherPriorityWins() {
        val lowPriority = makeRule(id = 1, senderKey = "16216", requiredKeywords = "account", priority = 0)
        val highPriority = makeRule(id = 2, senderKey = "16216", requiredKeywords = "account", priority = 10, action = SmsTeachingRule.ACTION_CREDIT)
        val result = findMatch(listOf(lowPriority, highPriority), "16216", "Tk 100 account update")
        assertNotNull(result)
        assertEquals(SmsTeachingRule.ACTION_CREDIT, result!!.action)
    }

    @Test
    fun findBestMatch_moreKeywordsWins() {
        val fewKeywords = makeRule(id = 1, senderKey = "16216", requiredKeywords = "account")
        val manyKeywords = makeRule(id = 2, senderKey = "16216", requiredKeywords = "account|debited|TK", action = SmsTeachingRule.ACTION_CREDIT)
        val result = findMatch(listOf(fewKeywords, manyKeywords), "16216", "Tk 100 debited from account")
        assertNotNull(result)
        assertEquals(SmsTeachingRule.ACTION_CREDIT, result!!.action)
    }

    @Test
    fun findBestMatch_conflictReturnsNull() {
        val rule1 = makeRule(id = 1, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_CREDIT, priority = 5)
        val rule2 = makeRule(id = 2, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_DEBIT, priority = 5)
        val result = findMatch(listOf(rule1, rule2), "16216", "Tk 100 account update")
        assertNull(result)
    }

    @Test
    fun findBestMatch_bodyOnlyRule() {
        val rule = makeRule(requiredKeywords = "debited|TK")
        val result = findMatch(listOf(rule), null, "Tk 100 debited from TK account")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_caseInsensitive() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = "DEBITED")
        val result = findMatch(listOf(rule), "16216", "Tk 100 debited from account")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_senderSubstring() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = "debited")
        val result = findMatch(listOf(rule), "16216BANK", "Tk 100 debited from account")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_ignoreAction() {
        val rule = makeRule(senderKey = "16216", action = SmsTeachingRule.ACTION_IGNORE)
        val result = findMatch(listOf(rule), "16216", "Any message body")
        assertNotNull(result)
        assertEquals(SmsTeachingRule.ACTION_IGNORE, result!!.action)
    }

    @Test
    fun findBestMatch_noSenderRequired_allRequiredPresent() {
        val rule = makeRule(requiredKeywords = "debited|Tk")
        val result = findMatch(listOf(rule), "16216", "Tk 500 debited from account")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_conflictSameActionSameAccount_noConflict() {
        val rule1 = makeRule(id = 1, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_CREDIT, priority = 5, accountId = 10L)
        val rule2 = makeRule(id = 2, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_CREDIT, priority = 5, accountId = 10L)
        val result = findMatch(listOf(rule1, rule2), "16216", "Tk 100 account update")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_orderIndependent() {
        val rule1 = makeRule(id = 1, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_CREDIT, priority = 5)
        val rule2 = makeRule(id = 2, senderKey = "16216", requiredKeywords = "account", action = SmsTeachingRule.ACTION_CREDIT, priority = 5)
        // Same rules in reverse order — should still find a match
        val result = findMatch(listOf(rule2, rule1), "16216", "Tk 100 account update")
        assertNotNull(result)
    }

    // --- Field Extraction (Phase 3) ---

    @Test
    fun extractAllFields_extractsAmountAndMaskAndBalance() {
        val body = "Tk 1,500.00 debited from A/C ***1234. Balance: Tk 5,000.00"
        val extracted = SmsTeachingEngine.extractAllFields(body)
        assertEquals(1500.00, extracted.amount!!, 0.01)
        assertEquals(5000.00, extracted.balance!!, 0.01)
        assertEquals("A/C ***1234", extracted.accountMask)
    }

    @Test
    fun generatePattern_safelyEscapesAndCapturesSpan() {
        val text = "Tk 1,250.00 debited from A/C ***1234"
        val span = "1,250.00"
        val pattern = SmsTeachingEngine.generatePattern(text, span, "amount")
        assertNotNull(pattern)
        assert(pattern!!.contains("(?<amount>\\Q1,250.00\\E)"))
    }

    // --- JSON keyword storage (plan §3) ---

    @Test
    fun findBestMatch_jsonRequiredKeywords_matches() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = """["debited"]""")
        val result = findMatch(listOf(rule), "16216", "Tk 100 debited from account")
        assertNotNull(result)
    }

    @Test
    fun findBestMatch_jsonRequiredKeywords_missingFails() {
        val rule = makeRule(senderKey = "16216", requiredKeywords = """["credited"]""")
        val result = findMatch(listOf(rule), "16216", "Tk 100 debited from account")
        assertNull(result)
    }

    @Test
    fun findBestMatch_jsonExcludedKeywords_presentFails() {
        val rule = makeRule(
            senderKey = "16216",
            requiredKeywords = """["account"]""",
            excludedKeywords = """["OTP"]"""
        )
        val result = findMatch(listOf(rule), "16216", "Your OTP is 123456 for account")
        assertNull(result)
    }

    @Test
    fun findBestMatch_dangerousBroadRule_returnsNull() {
        // No sender, no required keywords => would match every SMS (plan §3.2).
        val rule = makeRule(requiredKeywords = com.finmagic.data.local.KeywordJson.encode(emptyList()))
        val result = findMatch(listOf(rule), "16216", "any random body")
        assertNull(result)
    }

    @Test
    fun findBestMatch_broadRuleWithKeyword_stillApplies() {
        // A body-only rule WITH keywords is specific enough to apply.
        val rule = makeRule(requiredKeywords = """["debited"]""")
        val result = findMatch(listOf(rule), null, "Tk 100 debited from account")
        assertNotNull(result)
    }

    @Test
    fun matchesRule_matchesAllOlderAndNewerHistoricalSmsOfSamePattern() {
        val rule = makeRule(
            id = 42L,
            senderKey = "BRAC",
            requiredKeywords = """["debited","A/C"]""",
            action = SmsTeachingRule.ACTION_DEBIT
        )
        val historicalSms = listOf(
            "Tk 1,200.00 debited from A/C ***5678 on 01/02/2026",
            "Tk 3,450.50 debited from A/C ***5678 on 14/02/2026",
            "Your OTP 991234 for BRAC netbanking"
        )
        val matched = historicalSms.filter { body ->
            SmsTeachingEngine.matchesRule(rule, "BRACBANK", SmsTeachingEngine.normalizeText(body))
        }
        assertEquals(2, matched.size)
        assertEquals(1200.00, SmsTeachingEngine.extractAmount(matched[0])!!, 0.01)
        assertEquals(3450.50, SmsTeachingEngine.extractAmount(matched[1])!!, 0.01)
    }
}
