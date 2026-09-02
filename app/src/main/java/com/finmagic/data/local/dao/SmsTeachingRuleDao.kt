package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.finmagic.data.local.entity.SmsTeachingRule
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsTeachingRuleDao {
    @Insert
    suspend fun insert(rule: SmsTeachingRule): Long

    @Update
    suspend fun update(rule: SmsTeachingRule)

    @Query("SELECT * FROM sms_teaching_rules WHERE enabled = 1 ORDER BY priority DESC, createdAt DESC")
    suspend fun getEnabledOnce(): List<SmsTeachingRule>

    @Query("SELECT * FROM sms_teaching_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SmsTeachingRule>>

    @Query("SELECT * FROM sms_teaching_rules WHERE id = :id")
    suspend fun getById(id: Long): SmsTeachingRule?

    @Query("UPDATE sms_teaching_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sms_teaching_rules SET matchCount = matchCount + 1, lastMatchedAt = :now WHERE id = :id")
    suspend fun incrementMatchCount(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE sms_teaching_rules SET enabled = 0")
    suspend fun disableAll()

    @Query("UPDATE sms_teaching_rules SET enabled = 1")
    suspend fun enableAll()

    @Query("DELETE FROM sms_teaching_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM sms_teaching_rules WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>
}
