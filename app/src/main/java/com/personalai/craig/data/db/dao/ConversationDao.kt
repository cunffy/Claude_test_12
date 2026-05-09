package com.personalai.craig.data.db.dao

import androidx.room.*
import com.personalai.craig.data.db.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conv: ConversationEntity): Long

    @Update
    suspend fun update(conv: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM conversations WHERE isSummarized = 0 AND updatedAt < :cutoff ORDER BY updatedAt ASC")
    suspend fun getUnsummarized(cutoff: Long): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE isSummarized = 1 ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentSummarized(limit: Int): List<ConversationEntity>
}
