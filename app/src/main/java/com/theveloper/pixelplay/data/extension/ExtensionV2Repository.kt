package com.theveloper.pixelplay.data.extension

import android.content.Context
import com.theveloper.pixelplay.extension.api.ExtensionLyrics
import com.theveloper.pixelplay.extension.api.ExtensionSection
import com.theveloper.pixelplay.extension.api.ExtensionSetting
import com.theveloper.pixelplay.extension.api.ExtensionTrack
import com.theveloper.pixelplay.extension.api.MusicExtensionV2
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics, discover shelves, collection expansion and settings for extensions that implement
 * [MusicExtensionV2]. Sits next to [ExtensionRepository] and reads its loaded-extension list;
 * extensions without V2 support are treated as "nothing to offer", not as errors.
 */
@Singleton
class ExtensionV2Repository @Inject constructor(
    @ApplicationContext context: Context,
    private val base: ExtensionRepository
) {
    // Same file and key scheme as ExtensionRepository.hostContextFor(), so what settings write is what getPref() reads.
    private val prefs = context.getSharedPreferences("pixelplay_extensions", Context.MODE_PRIVATE)

    private fun loaded(extensionId: String) = base.extensions.value.firstOrNull { it.metadata.id == extensionId }

    /** Synced or plain lyrics. Success(null) means none found or the extension doesn't offer lyrics. */
    suspend fun getLyrics(
        extensionId: String,
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long?
    ): ExtensionCallResult<ExtensionLyrics?> {
        val ext = loaded(extensionId)?.takeIf { it.isEnabled }
            ?: return ExtensionCallResult.Failed(extensionId, "Extension not loaded or disabled")
        val v2 = ext.instance as? MusicExtensionV2 ?: return ExtensionCallResult.Success(null)
        return runCatching { v2.getLyrics(trackId, title, artist, durationMs) }.fold(
            onSuccess = { ExtensionCallResult.Success(it) },
            onFailure = { e ->
                Timber.w(e, "ExtensionV2Repository: getLyrics failed for $extensionId")
                ExtensionCallResult.Failed(extensionId, e.message ?: "Unknown error")
            }
        )
    }

    /** Discover shelves from every enabled extension that has any. */
    suspend fun getHome(): Map<String, ExtensionCallResult<List<ExtensionSection>>> {
        val result = linkedMapOf<String, ExtensionCallResult<List<ExtensionSection>>>()
        for (ext in base.extensions.value.filter { it.isEnabled }) {
            val v2 = ext.instance as? MusicExtensionV2 ?: continue
            runCatching { v2.getHome() }.fold(
                onSuccess = { if (it.isNotEmpty()) result[ext.metadata.id] = ExtensionCallResult.Success(it) },
                onFailure = { e ->
                    Timber.w(e, "ExtensionV2Repository: getHome failed for ${ext.metadata.id}")
                    result[ext.metadata.id] = ExtensionCallResult.Failed(ext.metadata.id, e.message ?: "Unknown error")
                }
            )
        }
        return result
    }

    /** True for enabled extensions that implement [MusicExtensionV2], i.e. the ones that can own a Discover tab. */
    fun supportsDiscover(ext: LoadedExtension): Boolean = ext.isEnabled && ext.instance is MusicExtensionV2

    /**
     * Discover shelves from a single extension, so each Discover tab loads (and fails) on its own.
     * An empty list is a valid success: the extension is fine, it just has nothing to show.
     */
    suspend fun getHomeFor(extensionId: String): ExtensionCallResult<List<ExtensionSection>> {
        val ext = loaded(extensionId)?.takeIf { it.isEnabled }
            ?: return ExtensionCallResult.Failed(extensionId, "Extension not loaded or disabled")
        val v2 = ext.instance as? MusicExtensionV2
            ?: return ExtensionCallResult.Failed(extensionId, "This extension doesn't offer a Discover feed")
        return try {
            ExtensionCallResult.Success(withContext(Dispatchers.IO) { v2.getHome() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.w(e, "ExtensionV2Repository: getHomeFor failed for $extensionId")
            ExtensionCallResult.Failed(extensionId, e.message ?: "Unknown error")
        }
    }

    /**
     * Expands an album/playlist shelf item into tracks, in order. This is what "Play album",
     * "Add to queue" and "Play next" on a collection should call before handing the tracks to the queue.
     */
    suspend fun getCollectionTracks(extensionId: String, collectionId: String): ExtensionCallResult<List<ExtensionTrack>> {
        val ext = loaded(extensionId)?.takeIf { it.isEnabled }
            ?: return ExtensionCallResult.Failed(extensionId, "Extension not loaded or disabled")
        val v2 = ext.instance as? MusicExtensionV2
            ?: return ExtensionCallResult.Failed(extensionId, "This extension doesn't support collections")
        return runCatching { v2.getCollectionTracks(collectionId) }.fold(
            onSuccess = { ExtensionCallResult.Success(it) },
            onFailure = { e ->
                Timber.w(e, "ExtensionV2Repository: getCollectionTracks failed for $extensionId")
                ExtensionCallResult.Failed(extensionId, e.message ?: "Unknown error")
            }
        )
    }

    /** Settings fields an extension declares. Available even while the extension is switched off. */
    fun settingsFor(extensionId: String): List<ExtensionSetting> =
        (loaded(extensionId)?.instance as? MusicExtensionV2)?.settings.orEmpty()

    fun getSetting(extensionId: String, key: String): String? =
        prefs.getString("data:$extensionId:$key", null)

    fun setSetting(extensionId: String, key: String, value: String?) {
        prefs.edit().putString("data:$extensionId:$key", value).apply()
    }
}
