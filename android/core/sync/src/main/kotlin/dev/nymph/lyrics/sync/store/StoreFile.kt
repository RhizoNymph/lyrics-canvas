package dev.nymph.lyrics.sync.store

import dev.nymph.lyrics.model.LyricsJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The store's own JSON: the API's rules plus a `kind` discriminator for the sealed types. */
val StoreJson: Json = Json(from = LyricsJson) { classDiscriminator = "kind" }

/** What reading the store file found. */
sealed interface StoreRead {
    data object Missing : StoreRead

    data class Loaded(val data: LocalData) : StoreRead

    /** Unreadable: set aside (not deleted) so nothing is lost silently; the app starts empty. */
    data class Corrupt(val reason: String, val setAsideAs: String?) : StoreRead
}

/** Why a write failed; the in-memory copy stays current and the next write tries again. */
data class StoreWriteError(val reason: String)

/** Where the local copy is kept. */
interface StoreFile {
    suspend fun read(): StoreRead

    /** Null on success. */
    suspend fun write(data: LocalData): StoreWriteError?

    suspend fun delete()
}

/** For tests (and a process without storage): kept in memory only. */
class InMemoryStoreFile(initial: LocalData? = null) : StoreFile {
    var saved: LocalData? = initial
        private set
    var writes: Int = 0
        private set

    override suspend fun read(): StoreRead = saved?.let(StoreRead::Loaded) ?: StoreRead.Missing

    override suspend fun write(data: LocalData): StoreWriteError? {
        saved = data
        writes++
        return null
    }

    override suspend fun delete() {
        saved = null
    }
}

/**
 * One JSON file, replaced atomically on every write (a temp file in the same directory, then a
 * rename), so a crash leaves either the old or the new copy, never half of one.
 */
class JsonStoreFile(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) : StoreFile {
    override suspend fun read(): StoreRead = withContext(io) {
        if (!file.exists()) return@withContext StoreRead.Missing
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return@withContext StoreRead.Corrupt("read failed: ${e.message}", null)
        }
        try {
            StoreRead.Loaded(StoreJson.decodeFromString(LocalData.serializer(), text))
        } catch (e: SerializationException) {
            StoreRead.Corrupt("decode failed: ${e.message}", setAside())
        } catch (e: IllegalArgumentException) {
            StoreRead.Corrupt("decode failed: ${e.message}", setAside())
        }
    }

    private fun setAside(): String? {
        val aside = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        return if (file.renameTo(aside)) aside.name else null
    }

    override suspend fun write(data: LocalData): StoreWriteError? = withContext(io) {
        val text = StoreJson.encodeToString(LocalData.serializer(), data)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            null
        } catch (e: IOException) {
            StoreWriteError(e.message ?: e::class.java.simpleName)
        }
    }

    override suspend fun delete() {
        withContext(io) {
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }
}
