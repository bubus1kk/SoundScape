package com.soundscape.app.core.domain.model

import java.time.Instant
import java.util.UUID

/** Editorial work is tied to a place and an exact server revision. */
data class EditorialRevision(val placeId: UUID, val revision: Int)

/** Editable payload. Incomplete compositions can be kept as drafts. */
data class EditorialContent(
    val title: String,
    val description: String,
    val coverPath: String?,
    val genreIds: List<Int>,
    val tagIds: List<Int>,
    val trackIds: List<UUID>,
)

/** Owner approval recorded against one revision. */
data class EditorialApproval(val revision: Int, val approvedBy: UUID, val approvedAt: Instant)

/** Editorial draft, its approval and independently published revision. */
data class EditorialDraft(
    val version: EditorialRevision,
    val playlistId: UUID?,
    val content: EditorialContent,
    val approval: EditorialApproval?,
    val publishedRevision: Int?,
)

/** Verified evidence returned by the catalog for editorial review. */
enum class EditorialSource { VERIFIED_IN_APP, VERIFIED_EXTERNAL_ONLY, RECHECK_REQUIRED, UNVERIFIED }

/** Composition and server readiness issues reported against a revision. */
enum class EditorialIssue {
    CITY_NOT_ON_PUBLIC_MAP, TITLE_REQUIRED, DESCRIPTION_REQUIRED, COVER_REQUIRED_OR_INVALID,
    GENRES_REQUIRED, TAGS_REQUIRED, TRACK_COUNT_12_TO_15, DUPLICATE_TRACK, UNKNOWN_TRACK,
    IN_APP_RECHECK_REQUIRED, NO_VERIFIED_SOURCE, NOT_ENOUGH_EXTERNAL_ONLY, TOO_MANY_EXTERNAL_ONLY, NOT_ENOUGH_IN_APP,
}

/** A readiness issue can identify a particular recording. */
data class EditorialFinding(val code: EditorialIssue, val trackId: UUID? = null)

/** Readiness snapshot supplied by the server. */
data class EditorialReadiness(
    val revision: Int,
    val issues: List<EditorialFinding>,
    val readyForApproval: Boolean,
    val readyForPublication: Boolean,
    val approvalValid: Boolean,
    val inAppCount: Int,
    val externalOnlyCount: Int,
)

private const val MIN_EDITORIAL_TRACKS = 12
private const val MAX_EDITORIAL_TRACKS = 15
private const val MAX_EXTERNAL_TRACKS = 3

/** Evaluates draft composition using the available source evidence. */
fun checkEditorialComposition(ids: List<UUID>, sources: Map<UUID, EditorialSource>): List<EditorialFinding> =
    buildList {
        if (ids.size !in MIN_EDITORIAL_TRACKS..MAX_EDITORIAL_TRACKS) {
            add(EditorialFinding(EditorialIssue.TRACK_COUNT_12_TO_15))
        }
        val unique = ids.toSet()
        if (unique.size != ids.size) add(EditorialFinding(EditorialIssue.DUPLICATE_TRACK))
        val external = unique.count { sources[it] == EditorialSource.VERIFIED_EXTERNAL_ONLY }
        if (external < 2) add(EditorialFinding(EditorialIssue.NOT_ENOUGH_EXTERNAL_ONLY))
        if (external > MAX_EXTERNAL_TRACKS) add(EditorialFinding(EditorialIssue.TOO_MANY_EXTERNAL_ONLY))
        for (id in unique) {
            when (sources[id]) {
                EditorialSource.VERIFIED_IN_APP, EditorialSource.VERIFIED_EXTERNAL_ONLY -> Unit
                EditorialSource.RECHECK_REQUIRED -> add(EditorialFinding(EditorialIssue.IN_APP_RECHECK_REQUIRED, id))
                EditorialSource.UNVERIFIED -> add(EditorialFinding(EditorialIssue.NO_VERIFIED_SOURCE, id))
                null -> add(EditorialFinding(EditorialIssue.UNKNOWN_TRACK, id))
            }
        }
    }
