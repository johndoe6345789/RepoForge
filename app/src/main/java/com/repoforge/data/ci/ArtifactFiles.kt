package com.repoforge.data.ci

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.repoforge.data.forge.safeFileName
import com.repoforge.data.model.CiArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** One file inside a downloaded artifact archive. */
data class ArchiveEntry(val name: String, val size: Long) {
    val fileName: String get() = name.substringAfterLast('/')
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)
}

/**
 * Downloaded artifacts live in the cache until the user saves one to Downloads, opens a file
 * from it, or installs an APK it contains.
 */
class ArtifactFiles(private val context: Context) {
    private val root = File(context.cacheDir, "artifacts")

    /** Where an artifact's archive is downloaded to. */
    fun archive(artifact: CiArtifact): File = File(File(root, safeFileName(artifact.id)), artifact.fileName)

    /** Removes downloads older than a day, so large archives don't pile up in the cache. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    suspend fun entries(archive: File): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        runCatching {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.map { ArchiveEntry(it.name, it.size) }.toList()
            }
        }.getOrDefault(emptyList()) // not a zip: offer the file as it is
    }

    /** Extracts one entry next to the archive. Only the base name is used, so paths can't escape. */
    suspend fun extract(archive: File, entry: ArchiveEntry): File = withContext(Dispatchers.IO) {
        val target = File(File(archive.parentFile, "files"), safeFileName(entry.fileName))
        if (target.exists() && target.length() == entry.size) return@withContext target
        target.parentFile!!.mkdirs()
        ZipFile(archive).use { zip ->
            val zipEntry = zip.getEntry(entry.name) ?: error("${entry.name} isn't in the archive")
            zip.getInputStream(zipEntry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
    }

    /**
     * Copies a file into the shared Downloads/RepoForge folder (app storage before Android 10,
     * which needs no permission). Returns where it went, for the user.
     */
    suspend fun saveToDownloads(file: File, name: String = file.name): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType(name))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RepoForge")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the download")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Downloads/RepoForge/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
            val target = File(dir, name)
            dir.mkdirs()
            file.copyTo(target, overwrite = true)
            target.path
        }
    }

    /** An intent that opens [file] in another app, or installs it if it's an APK. */
    fun viewIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        fun mimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
