package com.todo.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        OpLogEntity::class,
        MetaEntity::class,
        PreferenceEntity::class,
        LocalPrefEntity::class,
        TaskLocalEntity::class,
        LocalHlcEntity::class,
    ],
    version = TodoDatabase.VERSION,
    exportSchema = true,
)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun opLogDao(): OpLogDao

    abstract fun metaDao(): MetaDao

    abstract fun preferenceDao(): PreferenceDao

    abstract fun localPrefDao(): LocalPrefDao

    abstract fun taskLocalDao(): TaskLocalDao

    abstract fun localHlcDao(): LocalHlcDao

    companion object {
        const val VERSION = 1
        const val NAME = "todo.db"
    }
}
