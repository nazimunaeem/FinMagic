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
}
