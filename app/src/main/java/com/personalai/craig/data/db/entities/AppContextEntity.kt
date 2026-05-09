package com.personalai.craig.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_context")
data class AppContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val screenText: String,
    val windowTitle: String?,
    val timestamp: Long
)
