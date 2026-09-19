package com.theveloper.pixelplay.presentation.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.extension.ExtensionSongMapper
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.extension.api.ExtensionCollection
import com.theveloper.pixelplay.extension.api.ExtensionSection
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch

/**
 * Discover: one tab per enabled extension (YouTube, Spotify, ...). Each tab loads its own feed
 * lazily and fails independently, so a Spotify credentials error never blanks the YouTube tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenExtensions: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel()
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val tabStates by viewModel.tabStates.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DiscoverEvent.PlayTracks ->
                    playerViewModel.showAndPlaySong(event.start, event.songs, event.queueName)
                is DiscoverEvent.Message ->
                    scope.launch { snackbarHostState.showSnackbar(event.text) }
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val currentTab = tabs.getOrNull(pagerState.currentPage)

    // Load whichever tab is showing. Re-keyed on the tab id, so enabling an extension later
    // loads its feed the first time its page becomes current.
    LaunchedEffect(currentTab?.extensionId) {
        currentTab?.let { viewModel.ensureLoaded(it.extensionId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.discover_screen_title),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (currentTab != null) {
                        FilledTonalIconButton(
                            onClick = { viewModel.refresh(currentTab.extensionId) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.discover_refresh)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (tabs.isEmpty()) {
            DiscoverEmptyState(
                onOpenExtensions = onOpenExtensions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    tab.title,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    key = { tabs.getOrNull(it)?.extensionId ?: it }
                ) { page ->
                    val tab = tabs.getOrNull(page) ?: return@HorizontalPager
                    DiscoverTabPage(
                        tab = tab,
                        state = tabStates[tab.extensionId] ?: DiscoverTabState.Loading,
                        bottomPadding = padding.calculateBottomPadding(),
                        onRetry = { viewModel.refresh(tab.extensionId) },
                        onOpenExtensions = onOpenExtensions,
                        onTrackClick = { song, queue, queueName ->
                            playerViewModel.showAndPlaySong(song, queue, queueName)
                        },
                        onCollectionClick = { collection -> viewModel.playCollection(tab, collection) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverTabPage(
    tab: DiscoverTab,
    state: DiscoverTabState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
    onOpenExtensions: () -> Unit,
    onTrackClick: (Song, List<Song>, String) -> Unit,
    onCollectionClick: (ExtensionCollection) -> Unit
) {
    when (state) {
        DiscoverTabState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is DiscoverTabState.Error -> Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.discover_error_title, tab.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.discover_retry)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenExtensions) {
                Text(stringResource(R.string.discover_extension_settings))
            }
        }

        is DiscoverTabState.Ready -> if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.discover_tab_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding + 96.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(state.sections, key = { index, section -> "$index:${section.title}" }) { _, section ->
                    DiscoverSection(
                        tab = tab,
                        section = section,
                        onTrackClick = onTrackClick,
                        onCollectionClick = onCollectionClick
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverSection(
    tab: DiscoverTab,
    section: ExtensionSection,
    onTrackClick: (Song, List<Song>, String) -> Unit,
    onCollectionClick: (ExtensionCollection) -> Unit
) {
    // Mapped once per section so a tap queues the whole shelf, starting from the tapped track.
    val songs = remember(tab.extensionId, section) {
        section.tracks
            .map { ExtensionSongMapper.toSong(tab.extensionId, tab.title, it) }
            .distinctBy { it.id }
    }

    Column {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(10.dp))

        if (songs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    ShelfCard(
                        artworkUrl = song.albumArtUriString,
                        title = song.title,
                        subtitle = song.artist,
                        onClick = { onTrackClick(song, songs, section.title) }
                    )
                }
            }
        }

        if (section.collections.isNotEmpty()) {
            if (songs.isNotEmpty()) Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(section.collections.distinctBy { it.id }, key = { it.id }) { collection ->
                    ShelfCard(
                        artworkUrl = collection.artworkUrl,
                        title = collection.title,
                        subtitle = collection.subtitle,
                        onClick = { onCollectionClick(collection) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(
    artworkUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
    ) {
        SmartImage(
            model = artworkUrl,
            contentDescription = title,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiscoverEmptyState(
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.discover_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.discover_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenExtensions) {
                Icon(Icons.Rounded.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.discover_open_extensions))
            }
        }
    }
}
