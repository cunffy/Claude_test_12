package com.personalai.craig.data.db.dao

import androidx.room.*
import com.personalai.craig.data.db.entities.UserMemoryEntity

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memory ORDER BY updatedAt DESC")
    suspend fun getAll(): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memory WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): UserMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: UserMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(memories: List<UserMemoryEntity>)

    @Query("DELETE FROM user_memory WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM user_memory")
    suspend fun deleteAll()
}
