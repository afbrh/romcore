package com.noryan.romcore.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A user-made, named list of games. Shown as a tab right after GAMES, in [sortOrder] (then id) order. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
)

/** Membership. Keyed by games.id; deleting a game (file gone after a rescan) or a collection cascades here. */
@Entity(
    tableName = "collection_games",
    primaryKeys = ["collectionId", "gameId"],
    foreignKeys = [
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GameEntity::class, parentColumns = ["id"], childColumns = ["gameId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("gameId")],
)
data class CollectionGameEntity(val collectionId: Long, val gameId: Long)

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sortOrder, id") abstract fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collection_games") abstract fun observeMembers(): Flow<List<CollectionGameEntity>>
    @Query("SELECT * FROM collections ORDER BY sortOrder, id") abstract suspend fun collections(): List<CollectionEntity>
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM collections") protected abstract suspend fun maxSort(): Int
    @Insert protected abstract suspend fun insert(c: CollectionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertMembers(m: List<CollectionGameEntity>)
    @Query("UPDATE collections SET name = :name WHERE id = :id") abstract suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM collections WHERE id = :id") abstract suspend fun delete(id: Long)
    @Query("DELETE FROM collection_games WHERE collectionId = :id") protected abstract suspend fun clearMembers(id: Long)
    @Query("DELETE FROM collection_games WHERE collectionId = :cid AND gameId = :gid") abstract suspend fun removeMember(cid: Long, gid: Long)
    @Query("INSERT OR IGNORE INTO collection_games (collectionId, gameId) VALUES (:cid, :gid)") abstract suspend fun addMember(cid: Long, gid: Long)

    /** New collection at the end of the tab order; returns its id. */
    @Transaction open suspend fun create(name: String, gameIds: Collection<Long>): Long {
        val id = insert(CollectionEntity(name = name, sortOrder = maxSort() + 1))
        insertMembers(gameIds.map { CollectionGameEntity(id, it) })
        return id
    }

    /** Rename and replace the whole membership in one step. */
    @Transaction open suspend fun update(id: Long, name: String, gameIds: Collection<Long>) {
        rename(id, name)
        clearMembers(id)
        insertMembers(gameIds.map { CollectionGameEntity(id, it) })
    }
}
