package io.github.zyrouge.symphony.services.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.database.store.CloudFolderMappingStore
import io.github.zyrouge.symphony.services.database.store.PlaylistStore
import io.github.zyrouge.symphony.services.cloud.CloudFolderMapping
import io.github.zyrouge.symphony.services.groove.Playlist
import io.github.zyrouge.symphony.utils.RoomConvertors

@Database(
    entities = [Playlist::class, CloudFolderMapping::class],
    version = 2,
    autoMigrations = [
        AutoMigration(1, 2, PersistentDatabase.Migration1To2::class)
    ]
)
@TypeConverters(RoomConvertors::class)
abstract class PersistentDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistStore
    abstract fun cloudMappings(): CloudFolderMappingStore

    companion object {
        fun create(symphony: Symphony) = Room
            .databaseBuilder(
                symphony.applicationContext,
                PersistentDatabase::class.java,
                "persistent"
            )
            .build()
    }

    class Migration1To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `cloud_folder_mappings` (
                    `id` TEXT NOT NULL,
                    `localPath` TEXT NOT NULL,
                    `cloudPath` TEXT NOT NULL,
                    `cloudFolderId` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """)
        }
    }
}
