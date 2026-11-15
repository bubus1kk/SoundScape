package com.soundscape.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class DraftRevisionConflict : IllegalStateException()

@Suppress("TooManyFunctions")
@Dao
abstract class DiaryDraftDao {
    @Query("SELECT * FROM diary_drafts WHERE scope=:scope ORDER BY updatedAt DESC, id")
    abstract fun observeHeaders(scope: String): Flow<List<DiaryDraftEntity>>

    @Query("""SELECT COUNT(*) FROM diary_drafts d LEFT JOIN diary_draft_tracks t
        ON d.scope=t.scope AND d.id=t.draftId LEFT JOIN diary_draft_photos p
        ON d.scope=p.scope AND d.id=p.draftId WHERE d.scope=:scope""")
    abstract fun changes(scope: String): Flow<Long>

    @Query("SELECT * FROM diary_drafts WHERE scope=:scope ORDER BY updatedAt DESC, id")
    protected abstract suspend fun headers(scope: String): List<DiaryDraftEntity>

    @Query("SELECT * FROM diary_drafts WHERE scope=:scope AND id=:id")
    abstract suspend fun header(scope: String, id: String): DiaryDraftEntity?

    @Query("SELECT * FROM diary_draft_tracks WHERE scope=:scope AND draftId=:id ORDER BY position")
    abstract suspend fun tracks(scope: String, id: String): List<DiaryDraftTrackEntity>

    @Query("SELECT * FROM diary_draft_photos WHERE scope=:scope AND draftId=:id ORDER BY position")
    abstract suspend fun photos(scope: String, id: String): List<DiaryDraftPhotoEntity>

    @Query("SELECT * FROM diary_draft_photos")
    abstract suspend fun allPhotos(): List<DiaryDraftPhotoEntity>

    @Transaction
    open suspend fun snapshot(scope: String, id: String): DiaryDraftSnapshot? =
        header(scope, id)?.let { DiaryDraftSnapshot(it, tracks(scope, id), photos(scope, id)) }

    @Transaction
    open suspend fun snapshots(scope: String): List<DiaryDraftSnapshot> =
        headers(scope).map { DiaryDraftSnapshot(it, tracks(scope, it.id), photos(scope, it.id)) }

    @Transaction
    open suspend fun save(draft: DiaryDraftEntity, tracks: List<DiaryDraftTrackEntity>,
        photos: List<DiaryDraftPhotoEntity>, expectedRevision: Long?) {
        val current = header(draft.scope, draft.id)
        if (current?.revision != expectedRevision) throw DraftRevisionConflict()
        require(draft.revision == (expectedRevision ?: 0L) + 1)
        require(tracks.size <= MAX_TRACKS && photos.size <= MAX_PHOTOS)
        require(tracks.map { it.position } == tracks.indices.toList())
        require(photos.map { it.position } == photos.indices.toList())
        require(tracks.all { it.scope == draft.scope && it.draftId == draft.id })
        require(photos.all { it.scope == draft.scope && it.draftId == draft.id })
        if (current == null) insertHeader(draft) else updateHeader(draft)
        deleteTracks(draft.scope, draft.id)
        deletePhotos(draft.scope, draft.id)
        insertTracks(tracks)
        insertPhotos(photos)
    }

    @Transaction
    open suspend fun delete(scope: String, id: String, expectedRevision: Long) {
        if (header(scope, id)?.revision != expectedRevision) throw DraftRevisionConflict()
        deleteHeader(scope, id)
    }

    @Insert protected abstract suspend fun insertHeader(draft: DiaryDraftEntity)
    @Update protected abstract suspend fun updateHeader(draft: DiaryDraftEntity)
    @Insert protected abstract suspend fun insertTracks(tracks: List<DiaryDraftTrackEntity>)
    @Insert protected abstract suspend fun insertPhotos(photos: List<DiaryDraftPhotoEntity>)
    @Query("DELETE FROM diary_draft_tracks WHERE scope=:scope AND draftId=:id")
    protected abstract suspend fun deleteTracks(scope: String, id: String)
    @Query("DELETE FROM diary_draft_photos WHERE scope=:scope AND draftId=:id")
    protected abstract suspend fun deletePhotos(scope: String, id: String)
    @Query("DELETE FROM diary_drafts WHERE scope=:scope AND id=:id")
    protected abstract suspend fun deleteHeader(scope: String, id: String)
}

private const val MAX_TRACKS = 100
private const val MAX_PHOTOS = 10
