package com.finmagic.ui.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendHelperTest {
    @Test
    fun monthTrendPercent_usesPreviousMonthBaseline() {
        assertEquals(25.0, monthTrendPercent(current = 125.0, previous = 100.0), 0.01)
        assertEquals(-20.0, monthTrendPercent(current = 80.0, previous = 100.0), 0.01)
        assertEquals(0.0, monthTrendPercent(current = 100.0, previous = 0.0), 0.01)
    }
}
