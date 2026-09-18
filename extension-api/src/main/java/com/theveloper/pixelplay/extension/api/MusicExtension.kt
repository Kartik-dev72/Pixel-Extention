package com.theveloper.pixelplay.extension.api

/**
 * The contract every PixelPlay extension implements.
 *
 * IMPORTANT for extension authors: your `build.gradle.kts` must depend on this module with
 * `compileOnly`, not `implementation`. PixelPlay loads your extension's APK with a
 * `DexClassLoader` whose *parent* is the host app's own classloader — that's what lets a class
 * loaded from your APK be cast to `MusicExtension` inside the host process without a
 * ClassCastException. `compileOnly` means this interface satisfies the compiler but its bytecode
 * is never packaged into your extension's own APK, so at runtime there's only ever one copy of
 * this interface loaded — the host's — and class-loading delegation works correctly. If you
 * accidentally use `implementation`, your extension will fail to load with a ClassCastException
 * even though it compiles fine.
 *
 * A class implementing this interface must have a public no-argument constructor — the host
 * instantiates it via reflection.
 */
interface MusicExtension {

    /** Static, human-readable info shown in the Extensions screen before the extension is enabled. */
    val metadata: ExtensionMetadata

    /**
     * Called once, right after the host instantiates your extension and before any other method.
     * Do cheap setup here (read cached settings, etc.) — not network calls.
     */
    fun onCreate(context: ExtensionHostContext) {}

    /** Called when the user disables the extension or the app is shutting it down. Release resources here. */
    fun onDestroy() {}

    /**
     * True if this extension can currently serve requests — e.g. false if it requires login and
     * the user hasn't logged in yet. The host shows a "Connect" prompt instead of search results
     * when this is false and [ExtensionCapability.REQUIRES_LOGIN] is declared.
     */
    suspend fun isReady(): Boolean = true

    /** Search this extension's catalog. Return an empty list rather than throwing for "no results". */
    suspend fun search(query: String, page: ExtensionPage? = null): ExtensionSearchResult

    /**
     * Resolve a playable stream URL for a track previously returned by [search] (or [getRelated]).
     * [trackId] is whatever your own [ExtensionTrack.id] was — the host treats it as opaque.
     * Throw with a clear message on failure; the host surfaces it as a toast.
     */
    suspend fun resolveStreamUrl(trackId: String): String

    /** Optional: tracks related to a given one, for "up next" / radio-style features. */
    suspend fun getRelated(trackId: String): List<ExtensionTrack> = emptyList()

    /**
     * Optional: called when the host wants this extension to log a user in, if it declares
     * [ExtensionCapability.REQUIRES_LOGIN]. Return a URL for the host to open in a WebView, or
     * null if this extension handles login some other way (and calls back via [ExtensionHostContext]).
     */
    suspend fun getLoginUrl(): String? = null
}

/** Static info about an extension, shown before it's ever instantiated for real use. */
data class ExtensionMetadata(
    val id: String,
    val displayName: String,
    val version: String,
    val author: String,
    val capabilities: Set<ExtensionCapability>,
    /** Shown in the Extensions screen. Keep it to one sentence. */
    val description: String = ""
)

enum class ExtensionCapability {
    SEARCH,
    STREAM,
    RELATED,
    REQUIRES_LOGIN,
    DOWNLOAD
}

data class ExtensionTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    /** Set false for a live stream / non-seekable source so the host can adjust its player UI. */
    val isSeekable: Boolean = true
)

data class ExtensionPage(val token: String)

data class ExtensionSearchResult(
    val tracks: List<ExtensionTrack>,
    /** Non-null if there are more results; pass back into the next [MusicExtension.search] call. */
    val nextPage: ExtensionPage? = null
)

/**
 * Minimal capabilities the host gives an extension. Deliberately small — extensions are
 * sandboxed to "give me tracks and stream URLs", not given the host's database or file system.
 */
interface ExtensionHostContext {
    /** Per-extension key-value storage, persisted by the host (e.g. cached auth tokens). Small values only. */
    suspend fun getPref(key: String): String?
    suspend fun setPref(key: String, value: String?)

    /** Report a login success after the user completes an OAuth/WebView flow the host drove. */
    fun notifyLoginComplete()
}
