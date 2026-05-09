package com.personalai.craig.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_memory",
    indices = [Index(value = ["key"], unique = true)]
)
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val source: String,         // "user_stated" | "inferred" | "extracted"
    val confidence: Float = 1.0f,
    val createdAt: Long,
    val updatedAt: Long
)
