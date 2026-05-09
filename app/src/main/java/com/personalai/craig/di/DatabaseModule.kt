package com.personalai.craig.di

import android.content.Context
import androidx.room.Room
import com.personalai.craig.data.db.AppDatabase
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
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "craig.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideConversationDao(db: AppDatabase) = db.conversationDao()
    @Provides fun provideMessageDao(db: AppDatabase) = db.messageDao()
    @Provides fun provideUserMemoryDao(db: AppDatabase) = db.userMemoryDao()
    @Provides fun provideUserProfileDao(db: AppDatabase) = db.userProfileDao()
    @Provides fun provideAppContextDao(db: AppDatabase) = db.appContextDao()
}
