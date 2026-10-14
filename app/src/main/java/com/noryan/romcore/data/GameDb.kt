package com.noryan.romcore.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "games", indices = [Index(value = ["uri"], unique = true)])
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val realPath: String?,
    val fileName: String,
    val title: String,
    /** com.noryan.romcore.api.System name */
    val system: String,
    val sizeBytes: Long = 0,
    /** Per-game core override (core id) or null = automatic. */
    val coreOverride: String? = null,
    val favorite: Boolean = false,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    /** Unpacked title folder (Wii U loadiine layout): one game, not a file. */
    val isDir: Boolean = false,
    /** 6-char GameCube/Wii disc ID (GZ2E01...) read by the scanner; null for non-disc games or unreadable images. Used for routing. */
    val gameId: String? = null
)

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY favorite DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<GameEntity>>
    @Query("SELECT * FROM games") suspend fun all(): List<GameEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(g: List<GameEntity>)
    @Update suspend fun updateAll(g: List<GameEntity>)
    @Query("DELETE FROM games WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)
    @Query("DELETE FROM games") suspend fun clear()
    @Query("UPDATE games SET coreOverride = :core WHERE id = :id") suspend fun setCore(id: Long, core: String?)
    @Query("UPDATE games SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("UPDATE games SET lastPlayedAt = :t, playCount = playCount + 1 WHERE id = :id") suspend fun markPlayed(id: Long, t: Long)
}

@Database(entities = [GameEntity::class, CollectionEntity::class, CollectionGameEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun collections(): CollectionDao
}

/**
 * v3 -> v4: user collections. Two NEW tables only; `games` is not touched, so favourites, per-game core overrides and play
 * counts survive. The DDL is exactly what Room generates for [CollectionEntity] / [CollectionGameEntity] (it validates it on open).
 * Membership rows are keyed by games.id, which is stable across rescans (the scanner reuses a row's id by path and only
 * deletes the rows of games that vanished), and ON DELETE CASCADE drops those games from every collection silently.
 */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `collection_games` (`collectionId` INTEGER NOT NULL, `gameId` INTEGER NOT NULL, " +
            "PRIMARY KEY(`collectionId`, `gameId`), " +
            "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_games_gameId` ON `collection_games` (`gameId`)")
    }
}

/** v2 -> v3: games.gameId (disc ID for routing). Existing rows keep their favourites / overrides; the next scan fills the IDs in. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE games ADD COLUMN gameId TEXT")
    }
}
