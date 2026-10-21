package com.ink.sms.di

import android.content.Context
import androidx.room.Room
import com.ink.sms.data.dao.RecycleBinDao
import com.ink.sms.data.db.RecycleBinDatabase
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
    fun provideRecycleBinDatabase(
        @ApplicationContext context: Context
    ): RecycleBinDatabase =
        Room.databaseBuilder(context, RecycleBinDatabase::class.java, "recycle_bin.db")
            .build()

    @Provides
    @Singleton
    fun provideRecycleBinDao(db: RecycleBinDatabase): RecycleBinDao = db.dao()
}
