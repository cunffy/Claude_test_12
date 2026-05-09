package com.personalai.craig.data.repository

import com.personalai.craig.data.db.dao.UserMemoryDao
import com.personalai.craig.data.db.dao.UserProfileDao
import com.personalai.craig.data.db.entities.UserMemoryEntity
import com.personalai.craig.data.db.entities.UserProfileEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val userMemoryDao: UserMemoryDao,
    private val userProfileDao: UserProfileDao
) {
    suspend fun getAllMemory(): List<UserMemoryEntity> = userMemoryDao.getAll()

    suspend fun upsertMemory(key: String, value: String, source: String) {
        val now = System.currentTimeMillis()
        userMemoryDao.upsert(
            UserMemoryEntity(key = key, value = value, source = source, createdAt = now, updatedAt = now)
        )
    }

    suspend fun upsertMemoryBatch(memories: List<UserMemoryEntity>) =
        userMemoryDao.upsertAll(memories)

    suspend fun clearMemory() = userMemoryDao.deleteAll()

    suspend fun getProfileValue(key: String): String? = userProfileDao.getValue(key)

    suspend fun setProfileValue(key: String, value: String) =
        userProfileDao.upsert(UserProfileEntity(key = key, value = value))

    suspend fun getAllProfile(): List<UserProfileEntity> = userProfileDao.getAll()
}
