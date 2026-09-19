package com.theveloper.pixelplay.data.extension

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.theveloper.pixelplay.extension.api.ExtensionHostContext
import com.theveloper.pixelplay.extension.api.ExtensionMetadata
import com.theveloper.pixelplay.extension.api.ExtensionSearchResult
import com.theveloper.pixelplay.extension.api.MusicExtension
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedExtension(
    val filePath: String,
    val metadata: ExtensionMetadata,
    val isEnabled: Boolean,
    internal val instance: MusicExtension
)

sealed class ExtensionCallResult<out T> {
    data class Success<T>(val value: T) : ExtensionCallResult<T>()
    data class Failed(val id: String, val message: String) : ExtensionCallResult<Nothing>()
}

sealed class ImportResult {
    data class Success(val displayName: String) : ImportResult()
    data class Failed(val message: String) : ImportResult()
}

@Singleton
class ExtensionRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val discovery: ExtensionDiscovery,
    private val loader: ExtensionLoader
) {
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("pixelplay_extensions", Context.MODE_PRIVATE)

    private val loadMutex = Mutex()

    private val _extensions = MutableStateFlow<List<LoadedExtension>>(emptyList())
    val extensions: StateFlow<List<LoadedExtension>> = _extensions.asStateFlow()

    /**
     * Scans `filesDir/extensions/`, parses manifests, and loads each one. Call on app start and
     * after every successful [importExtension]. A single bad extension file is logged and
     * skipped — this never throws.
     */
    suspend fun refresh() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val discovered = discovery.discover(appContext)
            val loaded = mutableListOf<LoadedExtension>()

            for (fileMetadata in discovered) {
                when (val result = loader.load(appContext, fileMetadata)) {
                    is ExtensionLoadResult.Success -> {
                        val extension = result.instance
                        val enabled = isEnabled(fileMetadata.id)

                        runCatching {
                            extension.onCreate(hostContextFor(fileMetadata.id))
                        }.onFailure { e ->
                            Timber.e(e, "ExtensionRepository: onCreate threw for ${fileMetadata.id}")
                        }

                        loaded += LoadedExtension(
                            filePath = fileMetadata.filePath,
                            metadata = extension.metadata,
                            isEnabled = enabled,
                            instance = extension
                        )
                    }
                    is ExtensionLoadResult.Failure -> {
                        Timber.w("ExtensionRepository: skipping ${fileMetadata.id} — ${result.reason}")
                    }
                }
            }

            _extensions.value = loaded
        }
    }

    /**
     * Copies a user-picked file (from a Storage Access Framework file picker) into
     * `filesDir/extensions/`, then reloads. This — not an OS install step — is how a user adds
     * an extension: pick the .apk they downloaded, PixelPlay copies it into its own private
     * storage and loads it directly. Nothing is ever registered with the Android package
     * installer, so no launcher icon or separate "app" ever appears.
     */
    suspend fun importExtension(sourceUri: Uri, suggestedFileName: String): ImportResult =
        withContext(Dispatchers.IO) {
            val destination = ExtensionFiles.destinationFor(appContext, suggestedFileName)
            // Stage to a temp file, seal it read-only, then rename over the destination. A direct
            // write would fail with EACCES when re-importing over an existing read-only file;
            // rename(2) only needs write access to the directory. The ".tmp" suffix keeps staging
            // files out of ExtensionFiles.listExtensionFiles().
            val staging = File(destination.parentFile, destination.name + ".tmp")
            try {
                appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext ImportResult.Failed("Couldn't open the selected file")

                if (!ExtensionFiles.sealReadOnly(staging)) {
                    staging.delete()
                    return@withContext ImportResult.Failed("Couldn't secure the extension file")
                }
                if (!staging.renameTo(destination)) {
                    staging.delete()
                    return@withContext ImportResult.Failed("Couldn't install the extension file")
                }

                // Validate before committing to the extensions list — parse it the same way
                // discover() would, so a bad file is rejected up front with a clear reason
                // instead of silently failing on the next app start.
                val parsed = discovery.discover(appContext).firstOrNull { it.filePath == destination.absolutePath }
                if (parsed == null) {
                    destination.delete()
                    return@withContext ImportResult.Failed(
                        "This file doesn't look like a valid PixelPlay extension"
                    )
                }

                refresh()
                ImportResult.Success(parsed.displayName)
            } catch (e: Exception) {
                Timber.e(e, "ExtensionRepository: import failed")
                staging.delete()
                destination.delete()
                ImportResult.Failed(e.message ?: "Import failed")
            }
        }

    fun removeExtension(filePath: String) {
        ExtensionFiles.delete(appContext, filePath)
        _extensions.value = _extensions.value.filterNot { it.filePath == filePath }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(prefKey(id), enabled).apply()
        _extensions.value = _extensions.value.map {
            if (it.metadata.id == id) it.copy(isEnabled = enabled) else it
        }
    }

    private fun isEnabled(id: String): Boolean = prefs.getBoolean(prefKey(id), false)
    private fun prefKey(id: String) = "enabled:$id"

    /** Fan-out search across every enabled extension. Failures are reported per-extension, not thrown. */
    suspend fun searchAll(query: String): Map<String, ExtensionCallResult<ExtensionSearchResult>> {
        val enabled = _extensions.value.filter { it.isEnabled }
        return enabled.associate { ext ->
            ext.metadata.id to runCatching { ext.instance.search(query) }.fold(
                onSuccess = { ExtensionCallResult.Success(it) },
                onFailure = { e ->
                    Timber.w(e, "ExtensionRepository: search failed for ${ext.metadata.id}")
                    ExtensionCallResult.Failed(ext.metadata.id, e.message ?: "Unknown error")
                }
            )
        }
    }

    suspend fun resolveStreamUrl(extensionId: String, trackId: String): ExtensionCallResult<String> {
        val ext = _extensions.value.firstOrNull { it.metadata.id == extensionId }
            ?: return ExtensionCallResult.Failed(extensionId, "Extension not loaded")

        return runCatching { ext.instance.resolveStreamUrl(trackId) }.fold(
            onSuccess = { ExtensionCallResult.Success(it) },
            onFailure = { e -> ExtensionCallResult.Failed(extensionId, e.message ?: "Unknown error") }
        )
    }

    private fun hostContextFor(extensionId: String): ExtensionHostContext = object : ExtensionHostContext {
        override suspend fun getPref(key: String): String? =
            prefs.getString("data:$extensionId:$key", null)

        override suspend fun setPref(key: String, value: String?) {
            prefs.edit().putString("data:$extensionId:$key", value).apply()
        }

        override fun notifyLoginComplete() {
            // Hook for finer-grained "this one extension logged in" UI feedback later.
        }
    }
}
