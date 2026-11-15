package com.soundscape.app.core.data.diary

import com.soundscape.app.core.database.DiaryDraftDao
import com.soundscape.app.core.database.DiaryDraftEntity
import com.soundscape.app.core.database.DiaryDraftTrackEntity
import com.soundscape.app.core.database.DraftRevisionConflict
import com.soundscape.app.core.domain.model.DiaryDraft
import com.soundscape.app.core.domain.model.DiaryDraftInput
import com.soundscape.app.core.domain.model.DiaryDraftScope
import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.domain.repository.DiaryDraftRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomDiaryDraftRepository @Inject constructor(
    private val dao: DiaryDraftDao,
    private val photos: DiaryDraftPhotoStore,
    private val clock: Clock,
) : DiaryDraftRepository {
    override fun observe(scope: DiaryDraftScope): Flow<DomainResult<List<DiaryDraft>>> =
        flow {
            emitAll(dao.changes(scope.storageKey()).map<Long, DomainResult<List<DiaryDraft>>> {
                DomainResult.Success(dao.snapshots(scope.storageKey()).map { it.toDomain() })
            })
        }.catch { emit(DomainResult.Failure(DomainError.Unexpected)) }

    override fun observe(scope: DiaryDraftScope, id: UUID): Flow<DomainResult<DiaryDraft?>> =
        flow {
            emitAll(dao.changes(scope.storageKey()).map<Long, DomainResult<DiaryDraft?>> {
                DomainResult.Success(dao.snapshot(scope.storageKey(), id.toString())?.toDomain())
            })
        }.catch { emit(DomainResult.Failure(DomainError.Unexpected)) }

    override suspend fun get(scope: DiaryDraftScope, id: UUID): DomainResult<DiaryDraft?> = guarded {
        dao.snapshot(scope.storageKey(), id.toString())?.toDomain()
    }

    override suspend fun save(scope: DiaryDraftScope, id: UUID, input: DiaryDraftInput,
        expectedRevision: Long?): DomainResult<DiaryDraft> {
        val validation = input.validate()
        if (validation is DomainResult.Failure) return validation
        return guarded {
            photos.mutex.withLock {
                val key = scope.storageKey()
                val current = dao.header(key, id.toString())
                if (current?.revision != expectedRevision) throw DraftRevisionConflict()
                val now = maxOf(clock.millis(), current?.updatedAt ?: 0)
                val entity = DiaryDraftEntity(key, id.toString(), input.title, input.eventDate?.toString(),
                    input.locationName, input.coordinates?.latitude, input.coordinates?.longitude, input.body,
                    input.musicPinId?.toString(), input.visibility.name, current?.createdAt ?: now, now,
                    Math.addExact(expectedRevision ?: 0, 1))
                val attachments = photos.attachments(key, id.toString(), input.photos.map { it.id })
                val tracks = input.trackIds.mapIndexed { position, track ->
                    DiaryDraftTrackEntity(key, id.toString(), position, track.toString())
                }
                dao.save(entity, tracks, attachments, expectedRevision)
                try { photos.committed(input.photos.map { it.id }) } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) { Unit }
                DiaryDraft(id, scope, input, Instant.ofEpochMilli(entity.createdAt),
                    Instant.ofEpochMilli(now), entity.revision)
            }
        }
    }

    override suspend fun delete(scope: DiaryDraftScope, id: UUID, expectedRevision: Long): DomainResult<Unit> =
        guarded {
        photos.mutex.withLock {
            dao.delete(scope.storageKey(), id.toString(), expectedRevision)
            try { photos.reconcile() } catch (error: CancellationException) { throw error }
            catch (_: Exception) { Unit }
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): DomainResult<T> = withContext(Dispatchers.IO) {
        try { DomainResult.Success(block()) }
        catch (error: CancellationException) { throw error }
        catch (_: DraftRevisionConflict) { DomainResult.Failure(DomainError.VersionConflict) }
        catch (_: Exception) { DomainResult.Failure(DomainError.Unexpected) }
    }
}
