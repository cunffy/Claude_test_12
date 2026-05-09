package com.personalai.craig.data.db.dao

import androidx.room.*
import com.personalai.craig.data.db.entities.AppContextEntity

@Dao
interface AppContextDao {
    @Query("SELECT * FROM app_context ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): AppContextEntity?

    @Insert
    suspend fun insert(ctx: AppContextEntity)

    @Query("DELETE FROM app_context WHERE id NOT IN (SELECT id FROM app_context ORDER BY timestamp DESC LIMIT 10)")
    suspend fun pruneOld()
}
