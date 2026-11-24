package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.finmagic.data.local.entity.SmsRuleApplication
import kotlinx.coroutines.flow.Flow

data class AccountLinkedSms(
    val smsRecordId: Long,
    val accountId: Long
)

@Dao
interface SmsRuleApplicationDao {
    @Insert
    suspend fun insert(application: SmsRuleApplication): Long

    @Query("SELECT COUNT(*) FROM sms_rule_applications WHERE ruleId = :ruleId")
    suspend fun countByRule(ruleId: Long): Int

    @Query("SELECT * FROM sms_rule_applications WHERE smsRecordId = :smsRecordId")
    suspend fun getBySmsRecord(smsRecordId: Long): List<SmsRuleApplication>

    @Query("""
        SELECT a.smsRecordId AS smsRecordId, r.accountId AS accountId
        FROM sms_rule_applications a
        JOIN sms_teaching_rules r ON a.ruleId = r.id
        WHERE r.accountId > 0
    """)
    fun observeAccountLinkedSmsIds(): Flow<List<AccountLinkedSms>>

    @Query("""
        SELECT DISTINCT a.smsRecordId
        FROM sms_rule_applications a
        JOIN sms_teaching_rules r ON a.ruleId = r.id
        WHERE r.accountId = :accountId
    """)
    suspend fun getSmsRecordIdsForAccount(accountId: Long): List<Long>

    @Query("DELETE FROM sms_rule_applications WHERE smsRecordId IN (:smsRecordIds)")
    suspend fun deleteBySmsRecordIds(smsRecordIds: List<Long>)

    @Query("SELECT * FROM sms_rule_applications ORDER BY id ASC")
    suspend fun getAllOnce(): List<SmsRuleApplication>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(apps: List<SmsRuleApplication>)

    @Query("DELETE FROM sms_rule_applications")
    suspend fun clearAll()
}
