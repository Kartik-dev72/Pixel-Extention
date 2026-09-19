package com.theveloper.pixelplay.data.extension

import android.content.Context
import java.io.File

/**
 * Where extension files actually live: `filesDir/extensions/`, private app storage. Nothing here
 * is ever installed as a separate app — these are just files PixelPlay reads and loads directly.
 */
object ExtensionFiles {
    private const val DIR_NAME = "extensions"
    private val validExtensions = setOf("apk", "ppext")

    fun directory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun listExtensionFiles(context: Context): List<File> {
        val dir = directory(context)
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.extension.lowercase() in validExtensions }
    }

    /** Destination path a newly-imported extension file should be copied to. */
    fun destinationFor(context: Context, suggestedName: String): File {
        val safeName = suggestedName
            .substringAfterLast('/')
            .ifBlank { "extension_${System.currentTimeMillis()}" }
        val nameWithExt = if ('.' in safeName) safeName else "$safeName.apk"
        return File(directory(context), nameWithExt)
    }

    /**
     * Android 14+ (API 34) refuses to load dex/APK files the process can write to
     * ("Writable dex file ... is not allowed"). Call before any DexClassLoader use.
     * Returns true if the file is already read-only or was successfully sealed.
     */
    fun sealReadOnly(file: File): Boolean {
        if (!file.canWrite()) return true
        return file.setReadOnly() && !file.canWrite()
    }

    fun delete(context: Context, filePath: String): Boolean {
        val dir = directory(context).canonicalFile
        val target = File(filePath).canonicalFile
        // Guard against a path escaping the extensions directory.
        if (!target.path.startsWith(dir.path)) return false
        return target.delete()
    }
}
