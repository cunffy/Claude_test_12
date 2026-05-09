package com.personalai.craig.data.db.dao

import androidx.room.*
import com.personalai.craig.data.db.entities.UserProfileEntity

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile")
    suspend fun getAll(): List<UserProfileEntity>

    @Query("SELECT value FROM user_profile WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<UserProfileEntity>)
}
