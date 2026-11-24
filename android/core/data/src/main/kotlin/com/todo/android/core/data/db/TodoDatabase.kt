package com.todo.android.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OpLogEntity::class,
        MetaEntity::class,
        PreferenceEntity::class,
        LocalPrefEntity::class,
        TaskLocalEntity::class,
        LocalHlcEntity::class,
        DshClaimEntity::class,
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

    abstract fun dshClaimDao(): DshClaimDao

    companion object {
        const val VERSION = 2
        const val NAME = "todo.db"

        /** v1 → v2：新增跨进程提醒认领表，并给 task_local 补「谁展示的 / 何时展示」。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dsh_claim` (" +
                        "`dedupe_key` TEXT NOT NULL, " +
                        "`claimed_at` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`dedupe_key`))",
                )
                db.execSQL("ALTER TABLE `task_local` ADD COLUMN `fired_source` TEXT")
                db.execSQL("ALTER TABLE `task_local` ADD COLUMN `fired_at` INTEGER")
            }
        }
    }
}
