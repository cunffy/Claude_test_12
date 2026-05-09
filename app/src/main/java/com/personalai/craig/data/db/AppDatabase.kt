package com.personalai.craig.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.personalai.craig.data.db.dao.*
import com.personalai.craig.data.db.entities.*

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        UserMemoryEntity::class,
        UserProfileEntity::class,
        AppContextEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun appContextDao(): AppContextDao
}
