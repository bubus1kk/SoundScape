package com.soundscape.app.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "diary_drafts", primaryKeys = ["scope", "id"],
    indices = [Index(value = ["scope", "updatedAt", "id"])])
data class DiaryDraftEntity(
    val scope: String, val id: String, val title: String, val eventDate: String?,
    val locationName: String?, val latitude: Double?, val longitude: Double?, val body: String,
    val musicPinId: String?, val visibility: String, val createdAt: Long, val updatedAt: Long,
    val revision: Long,
)

@Entity(tableName = "diary_draft_tracks", primaryKeys = ["scope", "draftId", "position"],
    foreignKeys = [ForeignKey(entity = DiaryDraftEntity::class,
        parentColumns = ["scope", "id"], childColumns = ["scope", "draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["scope", "draftId", "trackId"], unique = true)])
data class DiaryDraftTrackEntity(val scope: String, val draftId: String, val position: Int, val trackId: String)

@Entity(tableName = "diary_draft_photos", primaryKeys = ["scope", "draftId", "position"],
    foreignKeys = [ForeignKey(entity = DiaryDraftEntity::class,
        parentColumns = ["scope", "id"], childColumns = ["scope", "draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["scope", "draftId", "photoId"], unique = true),
        Index(value = ["fileName"], unique = true)])
data class DiaryDraftPhotoEntity(
    val scope: String, val draftId: String, val position: Int, val photoId: String,
    val fileName: String, val mimeType: String, val byteCount: Long,
)

data class DiaryDraftSnapshot(
    val draft: DiaryDraftEntity,
    val tracks: List<DiaryDraftTrackEntity>,
    val photos: List<DiaryDraftPhotoEntity>,
)
