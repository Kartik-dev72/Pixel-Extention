package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.extension.ExtensionCallResult
import com.theveloper.pixelplay.data.extension.ExtensionRepository
import com.theveloper.pixelplay.data.extension.ExtensionSongMapper
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages search state and operations.
 *
 * Responsibilities:
 * - Local/library search
 * - Online song search through enabled extensions (YouTube, Spotify, ...)
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val extensionRepository: ExtensionRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MAX_EXTENSION_RESULTS_PER_SOURCE = 15
    }

    /** Fans the query out to every enabled extension; a failing extension is logged and skipped. */
    private suspend fun searchExtensions(query: String): List<Song> {
        val names = extensionRepository.extensions.value
            .filter { it.isEnabled }
            .associate { it.metadata.id to it.metadata.displayName }
        if (names.isEmpty()) return emptyList()

        return extensionRepository.searchAll(query).flatMap { (extensionId, result) ->
            when (result) {
                is ExtensionCallResult.Success ->
                    result.value.tracks
                        .take(MAX_EXTENSION_RESULTS_PER_SOURCE)
                        .map { ExtensionSongMapper.toSong(extensionId, names[extensionId] ?: extensionId, it) }
                is ExtensionCallResult.Failed -> {
                    Timber.w("Extension search failed for %s: %s", extensionId, result.message)
                    emptyList()
                }
            }
        }
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
    )

    // Search State
    private val _searchResults =
        MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())

    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter =
        MutableStateFlow(SearchFilterType.ALL)

    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory =
        MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())

    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()

        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->

                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value

                        /*
                         * ============================================================
                         * LOCAL SEARCH
                         * ============================================================
                         */

                        val localResults = withContext(Dispatchers.IO) {
                            musicRepository
                                .searchAll(
                                    normalizedQuery,
                                    currentFilter
                                )
                                .first()
                        }

                        if (
                            request.requestId !=
                            latestSearchRequestId.get()
                        ) {
                            return@collectLatest
                        }

                        val sortedLocalResults = localResults.sortedWith(
                            compareBy { result ->
                                when (result) {
                                    is SearchResultItem.SongItem -> 0
                                    is SearchResultItem.AlbumItem -> 1
                                    is SearchResultItem.ArtistItem -> 2
                                    is SearchResultItem.PlaylistItem -> 3
                                }
                            }
                        )

                        if (
                            request.requestId !=
                            latestSearchRequestId.get()
                        ) {
                            return@collectLatest
                        }

                        // Show local matches immediately; extension results are network-bound and
                        // get appended once they arrive.
                        val localOnly = sortedLocalResults.toImmutableList()
                        _searchResults.value = localOnly

                        val extensionSongs =
                            if (
                                currentFilter == SearchFilterType.ALL ||
                                currentFilter == SearchFilterType.SONGS
                            ) {
                                withContext(Dispatchers.IO) { searchExtensions(normalizedQuery) }
                            } else {
                                emptyList()
                            }

                        if (
                            request.requestId !=
                            latestSearchRequestId.get()
                        ) {
                            return@collectLatest
                        }

                        val existingSongIds =
                            sortedLocalResults
                                .asSequence()
                                .filterIsInstance<SearchResultItem.SongItem>()
                                .map { it.song.id }
                                .toHashSet()

                        val finalResults =
                            (
                                sortedLocalResults +
                                    extensionSongs
                                        .filter { it.id !in existingSongIds }
                                        .map { SearchResultItem.SongItem(it) }
                                ).toImmutableList()

                        if (
                            request.requestId ==
                            latestSearchRequestId.get()
                        ) {
                            _searchResults.value = finalResults

                            Timber.d(
                                "Search complete: local=%d, extensions=%d, total=%d, query=%s",
                                localOnly.size,
                                extensionSongs.size,
                                finalResults.size,
                                normalizedQuery
                            )
                        }

                    } catch (e: CancellationException) {
                        // A newer search request replaced this one.
                        throw e
                    } catch (e: Exception) {
                        if (
                            request.requestId ==
                            latestSearchRequestId.get()
                        ) {
                            Timber.e(
                                e,
                                "Error performing search for query: %s",
                                normalizedQuery
                            )

                            _searchResults.value =
                                persistentListOf()
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }

                _searchHistory.value =
                    history.toImmutableList()

            } catch (e: Exception) {
                Timber.e(
                    e,
                    "Error loading search history"
                )
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }

                    loadSearchHistory()

                } catch (e: Exception) {
                    Timber.e(
                        e,
                        "Error adding search history item"
                    )
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId =
            latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value =
                    persistentListOf()
            }
        }

        searchRequests.tryEmit(
            SearchRequest(
                query = normalizedQuery,
                requestId = requestId
            )
        )
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(
                        query
                    )
                }

                loadSearchHistory()

            } catch (e: Exception) {
                Timber.e(
                    e,
                    "Error deleting search history item"
                )
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }

                _searchHistory.value =
                    persistentListOf()

            } catch (e: Exception) {
                Timber.e(
                    e,
                    "Error clearing search history"
                )
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
