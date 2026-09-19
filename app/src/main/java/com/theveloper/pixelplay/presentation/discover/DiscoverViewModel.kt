package com.theveloper.pixelplay.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.extension.ExtensionCallResult
import com.theveloper.pixelplay.data.extension.ExtensionRepository
import com.theveloper.pixelplay.data.extension.ExtensionSongMapper
import com.theveloper.pixelplay.data.extension.ExtensionV2Repository
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.extension.api.ExtensionCollection
import com.theveloper.pixelplay.extension.api.ExtensionSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One Discover tab per enabled extension that offers a feed. */
data class DiscoverTab(val extensionId: String, val title: String)

sealed interface DiscoverTabState {
    data object Loading : DiscoverTabState
    data class Ready(val sections: List<ExtensionSection>) : DiscoverTabState
    data class Error(val message: String) : DiscoverTabState
}

sealed interface DiscoverEvent {
    /** Hand these to the player: [start] plays now, the rest fill the queue. */
    data class PlayTracks(val songs: List<Song>, val start: Song, val queueName: String) : DiscoverEvent
    data class Message(val text: String) : DiscoverEvent
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    repository: ExtensionRepository,
    private val v2: ExtensionV2Repository
) : ViewModel() {

    /** Reactive: enabling/disabling/importing an extension adds or removes its tab. */
    val tabs: StateFlow<List<DiscoverTab>> = repository.extensions
        .map { list ->
            list.filter(v2::supportsDiscover)
                .map { DiscoverTab(it.metadata.id, it.metadata.displayName) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _tabStates = MutableStateFlow<Map<String, DiscoverTabState>>(emptyMap())
    val tabStates: StateFlow<Map<String, DiscoverTabState>> = _tabStates.asStateFlow()

    private val _events = Channel<DiscoverEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val loadJobs = mutableMapOf<String, Job>()

    /** Called when a tab becomes visible. Loads once; use [refresh] to force a reload. */
    fun ensureLoaded(extensionId: String) {
        if (_tabStates.value.containsKey(extensionId)) return
        load(extensionId)
    }

    fun refresh(extensionId: String) = load(extensionId)

    private fun load(extensionId: String) {
        loadJobs[extensionId]?.cancel()
        _tabStates.update { it + (extensionId to DiscoverTabState.Loading) }
        loadJobs[extensionId] = viewModelScope.launch {
            val state = when (val result = v2.getHomeFor(extensionId)) {
                is ExtensionCallResult.Success -> DiscoverTabState.Ready(result.value)
                is ExtensionCallResult.Failed -> DiscoverTabState.Error(result.message)
            }
            _tabStates.update { it + (extensionId to state) }
        }
    }

    /** Expands an album/playlist shelf item into tracks and asks the screen to play them in order. */
    fun playCollection(tab: DiscoverTab, collection: ExtensionCollection) {
        viewModelScope.launch {
            when (val result = v2.getCollectionTracks(tab.extensionId, collection.id)) {
                is ExtensionCallResult.Success -> {
                    val songs = result.value.map { ExtensionSongMapper.toSong(tab.extensionId, tab.title, it) }
                    val first = songs.firstOrNull()
                    if (first == null) {
                        _events.send(DiscoverEvent.Message("\"${collection.title}\" has no playable tracks"))
                    } else {
                        _events.send(DiscoverEvent.PlayTracks(songs, first, collection.title))
                    }
                }
                is ExtensionCallResult.Failed -> _events.send(DiscoverEvent.Message(result.message))
            }
        }
    }
}
