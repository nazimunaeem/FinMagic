package com.finmagic.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SIM number normalization for wallet account masks (one wallet per SIM). */
class SimAccountResolverTest {

    @Test
    fun stripsCountryCodeWithPlus() {
        assertEquals("01729752392", SimAccountResolver.normalizeBdNumber("+8801729752392"))
    }

    @Test
    fun stripsCountryCodeWithoutPlus() {
        assertEquals("01729752392", SimAccountResolver.normalizeBdNumber("8801729752392"))
    }

    @Test
    fun keepsLocalFormat() {
        assertEquals("01729752392", SimAccountResolver.normalizeBdNumber("01729752392"))
    }

    @Test
    fun completesMissingLeadingZero() {
        assertEquals("01729752392", SimAccountResolver.normalizeBdNumber("1729752392"))
    }

    @Test
    fun rejectsNonMobileOrGarbage() {
        assertNull(SimAccountResolver.normalizeBdNumber(null))
        assertNull(SimAccountResolver.normalizeBdNumber(""))
        assertNull(SimAccountResolver.normalizeBdNumber("+880212345678")) // landline
        assertNull(SimAccountResolver.normalizeBdNumber("12345"))
    }
}
