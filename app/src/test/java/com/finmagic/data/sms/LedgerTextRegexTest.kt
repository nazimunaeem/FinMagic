package com.finmagic.data.sms

import com.finmagic.data.sms.parsers.LedgerText
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerTextRegexTest {
    @Test
    fun testPubaliNegativeBalance() {
        val body = "BDT 3031.4 has been credited to your a/c ***674 from MD NAZIM UDDIN on 27-JUL-26 through PI Banking.A/C Bal:-17874"
        val balance = LedgerText.parseBalance(body)
        assertEquals(-17874.0, balance)
    }
}
