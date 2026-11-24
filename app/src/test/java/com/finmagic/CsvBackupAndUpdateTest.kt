package com.finmagic

import com.finmagic.data.backup.CsvBackupManager
import com.finmagic.data.update.GitHubUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvBackupAndUpdateTest {

    @Test
    fun parseCsvRecords_handlesQuotedCommasDoubledQuotesAndCrlf() {
        val raw = "Record Type,Date,Account / Card,Counterparty\r\n" +
            "ACCOUNT_TXN,2026-11-20 14:30,\"City Bank (•••• 1234)\",\"Cafe, \"\"Bistro\"\"\"\r\n" +
            "\r\n" +
            "CARD_BILL,2026-11-21 09:00,EBL Visa (•••• 5678),Statement\r\n"

        val rows = CsvBackupManager.parseCsvRecords(raw)
        assertEquals(3, rows.size)
        assertEquals(listOf("Record Type", "Date", "Account / Card", "Counterparty"), rows[0])
        assertEquals(listOf("ACCOUNT_TXN", "2026-11-20 14:30", "City Bank (•••• 1234)", "Cafe, \"Bistro\""), rows[1])
        assertEquals(listOf("CARD_BILL", "2026-11-21 09:00", "EBL Visa (•••• 5678)", "Statement"), rows[2])
    }

    @Test
    fun parseCsvRecords_handlesMultilineQuotedField() {
        val raw = "Col1,Col2\n\"line1\nline2\",plain\n"
        val rows = CsvBackupManager.parseCsvRecords(raw)
        assertEquals(2, rows.size)
        assertEquals("line1\nline2", rows[1][0])
        assertEquals("plain", rows[1][1])
    }

    @Test
    fun isNewerVersion_comparesSemverAndPrereleaseTagsCorrectly() {
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.3", "1.0.2", 1L))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.10", "1.0.2", 1L))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v1.1.0-debug", "1.0.9", 1L))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.2", "1.0.2", 1L))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.2-debug", "1.0.2", 1L))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0.2", 1L))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("debug-latest", "1.0.2", 1L))
    }
}
