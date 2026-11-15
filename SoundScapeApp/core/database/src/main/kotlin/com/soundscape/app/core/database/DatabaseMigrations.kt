package com.soundscape.app.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS diary_drafts (
            scope TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, eventDate TEXT,
            locationName TEXT, latitude REAL, longitude REAL, body TEXT NOT NULL,
            musicPinId TEXT, visibility TEXT NOT NULL, createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(scope,id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_drafts_scope_updatedAt_id" +
            " ON diary_drafts(scope,updatedAt,id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS diary_draft_tracks (
            scope TEXT NOT NULL, draftId TEXT NOT NULL, position INTEGER NOT NULL, trackId TEXT NOT NULL,
            PRIMARY KEY(scope,draftId,position), FOREIGN KEY(scope,draftId)
            REFERENCES diary_drafts(scope,id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS" +
            " index_diary_draft_tracks_scope_draftId_trackId ON" +
            " diary_draft_tracks(scope,draftId,trackId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS diary_draft_photos (
            scope TEXT NOT NULL, draftId TEXT NOT NULL, position INTEGER NOT NULL, photoId TEXT NOT NULL,
            fileName TEXT NOT NULL, mimeType TEXT NOT NULL, byteCount INTEGER NOT NULL,
            PRIMARY KEY(scope,draftId,position), FOREIGN KEY(scope,draftId)
            REFERENCES diary_drafts(scope,id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS" +
            " index_diary_draft_photos_scope_draftId_photoId ON" +
            " diary_draft_photos(scope,draftId,photoId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS" +
            " index_diary_draft_photos_fileName ON" +
            " diary_draft_photos(fileName)")
    }
}
