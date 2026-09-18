package com.theveloper.pixelplay.data.extension

import android.content.Context
import android.content.SharedPreferences
import com.theveloper.pixelplay.extension.api.ExtensionHostContext
import com.theveloper.pixelplay.extension.api.ExtensionMetadata
import com.theveloper.pixelplay.extension.api.ExtensionPage
import com.theveloper.pixelplay.extension.api.ExtensionSearchResult
import com.theveloper.pixelplay.extension.api.ExtensionTrack
import com.theveloper.pixelplay.extension.api.MusicExtension
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class LoadedExtension(
    val packageName: String,
    val metadata: ExtensionMetadata,
    val isEnabled: Boolean,
    internal val instance: MusicExtension
)

/** One failed/crashed extension should never take down a search across all of them. */
sealed class ExtensionCallResult<out T> {
    data class Success<T>(val value: T) : ExtensionCallResult<T>()
    data class Failed(val packageName: String, val message: String) : ExtensionCallResult<Nothing>()
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
     * Discovers and loads every installed extension. Call on app start and after any
     * ACTION_PACKAGE_ADDED/_REMOVED for a package that turns out to be an extension.
     * A single bad extension is logged and skipped — this never throws.
     */
    suspend fun refresh() = loadMutex.withLock {
        val discovered = discovery.discover(appContext)
        val loaded = mutableListOf<LoadedExtension>()

        for (candidate in discovered) {
            when (val result = loader.load(appContext, candidate)) {
                is ExtensionLoadResult.Success -> {
                    val extension = result.instance
                    val enabled = isEnabled(candidate.packageName)

                    runCatching {
                        extension.onCreate(hostContextFor(candidate.packageName))
                    }.onFailure { e ->
                        Timber.e(e, "ExtensionRepository: onCreate threw for ${candidate.packageName}")
                    }

                    loaded += LoadedExtension(
                        packageName = candidate.packageName,
                        metadata = extension.metadata,
                        isEnabled = enabled,
                        instance = extension
                    )
                }
                is ExtensionLoadResult.Failure -> {
                    Timber.w("ExtensionRepository: skipping ${candidate.packageName} — ${result.reason}")
                }
            }
        }

        _extensions.value = loaded
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(prefKey(packageName), enabled).apply()
        _extensions.value = _extensions.value.map {
            if (it.packageName == packageName) it.copy(isEnabled = enabled) else it
        }
    }

    private fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean(prefKey(packageName), /* default */ false)

    private fun prefKey(packageName: String) = "enabled:$packageName"

    /** Fan-out search across every enabled extension. Failures are reported per-extension, not thrown. */
    suspend fun searchAll(query: String): Map<String, ExtensionCallResult<ExtensionSearchResult>> {
        val enabled = _extensions.value.filter { it.isEnabled }
        return enabled.associate { ext ->
            ext.packageName to runCatching {
                ext.instance.search(query)
            }.fold(
                onSuccess = { ExtensionCallResult.Success(it) },
                onFailure = { e ->
                    Timber.w(e, "ExtensionRepository: search failed for ${ext.packageName}")
                    ExtensionCallResult.Failed(ext.packageName, e.message ?: "Unknown error")
                }
            )
        }
    }

    suspend fun resolveStreamUrl(packageName: String, trackId: String): ExtensionCallResult<String> {
        val ext = _extensions.value.firstOrNull { it.packageName == packageName }
            ?: return ExtensionCallResult.Failed(packageName, "Extension not loaded")

        return runCatching { ext.instance.resolveStreamUrl(trackId) }.fold(
            onSuccess = { ExtensionCallResult.Success(it) },
            onFailure = { e -> ExtensionCallResult.Failed(packageName, e.message ?: "Unknown error") }
        )
    }

    private fun hostContextFor(packageName: String): ExtensionHostContext = object : ExtensionHostContext {
        override suspend fun getPref(key: String): String? =
            prefs.getString("data:$packageName:$key", null)

        override suspend fun setPref(key: String, value: String?) {
            prefs.edit().putString("data:$packageName:$key", value).apply()
        }

        override fun notifyLoginComplete() {
            // Re-trigger isReady() checks downstream; simplest correct approach is a refresh.
            // Left as a hook for the ViewModel layer to observe via a SharedFlow if finer-grained
            // "just this one extension logged in" UI feedback is wanted later.
        }
    }
}
