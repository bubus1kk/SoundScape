package com.soundscape.app.core.data.diary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.soundscape.app.core.database.DiaryDraftDao
import com.soundscape.app.core.database.DiaryDraftPhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class DiaryDraftPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DiaryDraftDao,
) {
    internal val mutex = Mutex()
    private val directory = File(context.noBackupFilesDir, "diary-photos")
    private val pending = mutableMapOf<UUID, DiaryDraftPhotoEntity>()
    private var initialized = false

    internal suspend fun reconcile() {
        val referenced = dao.allPhotos().map { it.fileName }.toSet()
        check(directory.isDirectory || directory.mkdirs())
        directory.listFiles()?.filter { it.name !in referenced && it.name !in pending.values.map { p -> p.fileName } }
            ?.forEach { check(it.delete() || !it.exists()) }
        initialized = true
    }

    private suspend fun initialize() { if (!initialized) reconcile() }

    suspend fun import(uri: Uri): UUID = importSafely(
        mime = { context.contentResolver.getType(uri) },
        open = { context.contentResolver.openInputStream(uri) })

    internal suspend fun importSource(mime: String?, onCreated: suspend (UUID) -> Unit = {},
        open: () -> java.io.InputStream?): UUID = importSafely({ mime }, open, onCreated)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun importSafely(mime: () -> String?, open: () -> java.io.InputStream?,
        onCreated: suspend (UUID) -> Unit = {}): UUID {
        var created: UUID? = null
        try {
            return withContext(Dispatchers.IO) {
                copySource(mime(), open) { id -> created = id; onCreated(id) }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try { created?.let { release(listOf(it)) } }
                catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    private suspend fun copySource(mime: String?, open: () -> java.io.InputStream?,
        onCreated: suspend (UUID) -> Unit): UUID {
        return mutex.withLock {
            initialize()
            require(mime in setOf("image/jpeg", "image/png", "image/webp"))
            val id = UUID.randomUUID()
            val temporary = File(directory, "$id.part")
            val destination = File(directory, "$id.image")
            try {
                var bytes = 0L
                open()?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= MAX_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: throw IOException("Unreadable image")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temporary.path, bounds)
                require(bytes > 0 && bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mime)
                coroutineContext.ensureActive()
                check(temporary.renameTo(destination))
                pending[id] = DiaryDraftPhotoEntity("", "", 0, id.toString(), destination.name, mime!!, bytes)
                onCreated(id)
                id
            } finally { temporary.delete() }
        }
    }

    internal suspend fun attachments(scope: String, draftId: String, ids: List<UUID>): List<DiaryDraftPhotoEntity> {
        initialize()
        val saved = dao.photos(scope, draftId).associateBy { UUID.fromString(it.photoId) }
        return ids.mapIndexed { position, id ->
            val photo = saved[id] ?: pending[id] ?: throw IOException("Missing photo")
            photo.copy(scope = scope, draftId = draftId, position = position)
        }
    }

    internal suspend fun committed(ids: List<UUID>) {
        ids.forEach { pending.remove(it) }
        reconcile()
    }

    suspend fun release(ids: List<UUID>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ids.forEach { pending.remove(it) }
            reconcile()
        }
    }

    suspend fun thumbnail(id: UUID): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            initialize()
            val photo = dao.allPhotos().find { it.photoId == id.toString() } ?: pending[id]
            photo?.let {
                val file = File(directory, it.fileName)
                require(file.parentFile?.canonicalFile == directory.canonicalFile)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                while (bounds.outWidth / options.inSampleSize > THUMBNAIL_SIZE
                    || bounds.outHeight / options.inSampleSize > THUMBNAIL_SIZE) {
                    options.inSampleSize *= 2
                }
                BitmapFactory.decodeFile(file.path, options)
            }
        }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        private const val THUMBNAIL_SIZE = 384
    }
}
