package com.finmagic.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the account-template auto-creation helper (plan §4/§5).
 * [SmsTeachingEngine.Companion.TemplateJson] encodes/decodes an [AccountTemplate]
 * attached to a rule so a rule can re-create its account if it is deleted.
 */
class SmsTeachingRuleTemplateTest {

    @Test
    fun templateJson_roundTrip_preservesFields() {
        val template = AccountTemplate(
            name = "bKash 01712…456",
            kind = "WALLET",
            bankName = "bKash",
            accountMask = "01712000456",
            currency = "BDT",
            includeInTotal = false
        )

        val json = SmsTeachingEngine.Companion.TemplateJson.encode(template)
        val decoded = SmsTeachingEngine.Companion.TemplateJson.decode(json)

        assertEquals(template.name, decoded!!.name)
        assertEquals(template.kind, decoded.kind)
        assertEquals(template.bankName, decoded.bankName)
        assertEquals(template.accountMask, decoded.accountMask)
        assertEquals(template.currency, decoded.currency)
        assertEquals(template.includeInTotal, decoded.includeInTotal)
    }

    @Test
    fun templateJson_decode_blankReturnsNull() {
        assertNull(SmsTeachingEngine.Companion.TemplateJson.decode(null))
        assertNull(SmsTeachingEngine.Companion.TemplateJson.decode(""))
    }

    @Test
    fun templateJson_decode_malformedReturnsNull() {
        assertNull(SmsTeachingEngine.Companion.TemplateJson.decode("not-json{"))
    }

    @Test
    fun templateJson_decode_blankNameReturnsNull() {
        val json = SmsTeachingEngine.Companion.TemplateJson.encode(
            AccountTemplate(name = "   ", kind = "BANK", accountMask = "**1234")
        )
        assertNull(SmsTeachingEngine.Companion.TemplateJson.decode(json))
    }
}
