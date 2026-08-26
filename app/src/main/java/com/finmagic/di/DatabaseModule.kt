package com.finmagic.di

import android.content.Context
import com.finmagic.data.local.AppDatabase
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.ActivityLogDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideCreditCardDao(database: AppDatabase): CreditCardDao {
        return database.creditCardDao()
    }

    @Provides
    fun provideBillDao(database: AppDatabase): BillDao {
        return database.billDao()
    }

    @Provides
    fun provideActivityLogDao(database: AppDatabase): ActivityLogDao {
        return database.activityLogDao()
    }

    @Provides
    fun provideSmsRecordDao(database: AppDatabase): SmsRecordDao {
        return database.smsRecordDao()
    }

    @Provides
    fun provideCardPaymentDao(database: AppDatabase): CardPaymentDao {
        return database.cardPaymentDao()
    }

    @Provides
    fun provideCardTransactionDao(database: AppDatabase): CardTransactionDao {
        return database.cardTransactionDao()
    }

    @Provides
    fun provideAccountDao(database: AppDatabase): AccountDao {
        return database.accountDao()
    }

    @Provides
    fun provideAccountTransactionDao(database: AppDatabase): AccountTransactionDao {
        return database.accountTransactionDao()
    }

    @Provides
    fun provideTransferDao(database: AppDatabase): TransferDao {
        return database.transferDao()
    }

    @Provides
    fun provideSmsTeachingRuleDao(database: AppDatabase): com.finmagic.data.local.dao.SmsTeachingRuleDao {
        return database.smsTeachingRuleDao()
    }

    @Provides
    fun provideSmsRuleApplicationDao(database: AppDatabase): com.finmagic.data.local.dao.SmsRuleApplicationDao {
        return database.smsRuleApplicationDao()
    }
}
