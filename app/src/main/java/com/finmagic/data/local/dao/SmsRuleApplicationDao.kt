package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.finmagic.data.local.entity.SmsRuleApplication

@Dao
interface SmsRuleApplicationDao {
    @Insert
    suspend fun insert(application: SmsRuleApplication): Long

    @Query("SELECT COUNT(*) FROM sms_rule_applications WHERE ruleId = :ruleId")
    suspend fun countByRule(ruleId: Long): Int

    @Query("SELECT * FROM sms_rule_applications WHERE smsRecordId = :smsRecordId")
    suspend fun getBySmsRecord(smsRecordId: Long): List<SmsRuleApplication>

    @Query("SELECT * FROM sms_rule_applications ORDER BY id ASC")
    suspend fun getAllOnce(): List<SmsRuleApplication>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(apps: List<SmsRuleApplication>)

    @Query("DELETE FROM sms_rule_applications")
    suspend fun clearAll()
}
