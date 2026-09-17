package com.theveloper.pixelplay.presentation.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.audio.MoodAnalyzer
import com.theveloper.pixelplay.data.repository.MoodPoint
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.viewmodel.LegacyExportUiState
import com.theveloper.pixelplay.presentation.viewmodel.LegacyImportUiState
import com.theveloper.pixelplay.presentation.viewmodel.MusicSquareViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The Music Square: a two-axis mood map of the library (calm↔exciting, sad↔joyful). Drag with
 * one finger to look around (pinch with two to zoom into album art), and double-tap anywhere
 * to drop a probe — PixelPlay finds the nearest-sounding tracks to that point and builds a mix
 * from them, the same way the desktop Music Square tool this is modeled on lets you click/drag
 * across its grid to build a playlist from mood-adjacent tracks.
 *
 * Positions come from [MusicSquareViewModel.moodPoints], which blends on-device audio analysis
 * (tempo, loudness, rhythmic density, brightness, tonality) with a genre-tag prior — see
 * [com.theveloper.pixelplay.data.repository.MoodRepository] for how those combine and get
 * normalized against the current library.
 */
@Composable
fun MusicSquareScreen(
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    viewModel: MusicSquareViewModel = hiltViewModel()
) {
    val moodPoints by viewModel.moodPoints.collectAsStateWithLifecycle()
    val progress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val analysisSnippetSeconds by viewModel.analysisSnippetSeconds.collectAsStateWithLifecycle()
    val analysisStartOffsetPercent by viewModel.analysisStartOffsetPercent.collectAsStateWithLifecycle()

    val toastContext = LocalContext.current
    var showSnippetChoice by remember { mutableStateOf(false) }

    val legacyImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importLegacyLibrary(it) }
    }

    val showSettingsDialog by viewModel.showSettingsDialog.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportLegacyLibrary(it) }
    }

    LaunchedEffect(exportState) {
        val state = exportState
        if (state is LegacyExportUiState.Done) {
            val message = state.result.error?.let {
                toastContext.getString(R.string.music_square_export_error, it)
            } ?: toastContext.getString(R.string.music_square_export_done, state.result.exported)
            Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
            viewModel.dismissExportResult()
        }
    }

    // Only start background analysis once we know the welcome pop-up has actually been
    // resolved — otherwise a first-time user would see analysis kick off underneath the dialog
    // before they've chosen import vs. analyze locally.
    LaunchedEffect(hasCompletedOnboarding) {
        if (hasCompletedOnboarding == true) {
            viewModel.ensureAnalysisRunning()
        }
    }

    // A library that already has mood data predates this welcome flow — treat it as
    // already onboarded instead of surprising a returning user with the pop-up.
    LaunchedEffect(hasCompletedOnboarding, moodPoints) {
        if (hasCompletedOnboarding == false && moodPoints.isNotEmpty()) {
            viewModel.markOnboardingCompleted()
        }
    }

    LaunchedEffect(importState) {
        val state = importState
        if (state is LegacyImportUiState.Done) {
            val message = state.result.error?.let {
                toastContext.getString(R.string.music_square_import_error, it)
            } ?: toastContext.getString(
                R.string.music_square_import_done,
                state.result.matched,
                state.result.totalInFile
            )
            Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
            viewModel.dismissImportResult()
        }
    }

    if (hasCompletedOnboarding == false && moodPoints.isEmpty()) {
        MusicSquareWelcomeDialog(
            onImportClick = { legacyImportLauncher.launch("application/json") },
            onAnalyzeLocallyClick = { showSnippetChoice = true }
        )
    }

    if (showSnippetChoice) {
        MusicSquareSnippetChoiceDialog(
            initialSnippetSeconds = analysisSnippetSeconds,
            initialOffsetPercent = analysisStartOffsetPercent,
            onSelect = { seconds, offsetPercent ->
                showSnippetChoice = false
                viewModel.chooseLocalAnalysis(seconds, offsetPercent)
            },
            onBack = { showSnippetChoice = false }
        )
    }

    if (showSettingsDialog) {
        MusicSquareSettingsDialog(
            onImportClick = {
                viewModel.closeSettingsDialog()
                legacyImportLauncher.launch("application/json")
            },
            onAnalyzeLocallyClick = {
                viewModel.closeSettingsDialog()
                showSnippetChoice = true
            },
            onExportClick = {
                viewModel.closeSettingsDialog()
                exportLauncher.launch("music-square-export.json")
            },
            onDismiss = { viewModel.closeSettingsDialog() }
        )
    }

    if (moodPoints.isEmpty()) {
        MusicSquareEmptyState(
            isAnalyzing = progress.isRunning,
            analyzed = progress.analyzed,
            total = progress.total,
            onAnalyzeClick = { viewModel.ensureAnalysisRunning() },
            bottomBarHeight = bottomBarHeight
        )
        return
    }

    var probe by remember { mutableStateOf<Offset?>(null) }
    var nearestPreview by remember { mutableStateOf<List<MoodPoint>>(emptyList()) }

    val neighborCount = 15

    // Pinch-to-zoom / pan state for the square. The canvas transform is still a simple affine
    // transform (screen = content * zoom + pan, see MoodSquareCanvas) so the tap-to-content
    // coordinate math below stays easy to invert — but the transform's *anchor* is computed
    // per-gesture from the pinch centroid (see the pointerInput below) rather than being pinned
    // to the top-left corner, so zooming in stays centered under your fingers instead of
    // dragging everything toward the corner.
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var squareSizePx by remember { mutableStateOf(IntSize.Zero) }

    // Album art is decoded lazily in the background for every dot (not blocking the initial
    // render) — capped and evicted (see MAX_LOADED_ART_TILES) so a huge library can't blow
    // past a reasonable memory budget.
    val context = LocalContext.current
    val artScope = rememberCoroutineScope()
    val albumArtCache = remember { mutableStateMapOf<String, ImageBitmap>() }
    val failedArtIds = remember { mutableSetOf<String>() }
    val inFlightArtIds = remember { mutableSetOf<String>() }
    val artLoadSemaphore = remember { Semaphore(6) }

    // At zoom=1 this "visible" rect covers the whole square, so on a typical library this
    // effectively means "load art for everything" — the viewport filter still matters once
    // you're zoomed in and panned somewhere, so off-screen songs don't compete for load slots.
    val visibleForArt = remember(zoom, pan, squareSizePx, moodPoints) {
        if (squareSizePx == IntSize.Zero) {
            emptyList()
        } else {
            val w = squareSizePx.width.toFloat()
            val h = squareSizePx.height.toFloat()
            val left = -pan.x / zoom
            val top = -pan.y / zoom
            val right = (w - pan.x) / zoom
            val bottom = (h - pan.y) / zoom
            moodPoints.filter { point ->
                val cx = point.x * w
                val cy = (1f - point.y) * h
                cx in left..right && cy in top..bottom
            }
        }
    }

    LaunchedEffect(visibleForArt) {
        val visibleIds = visibleForArt.mapTo(mutableSetOf()) { it.song.id }
        for (point in visibleForArt) {
            val id = point.song.id
            if (albumArtCache.containsKey(id) || id in inFlightArtIds || id in failedArtIds) continue
            if (albumArtCache.size >= MAX_LOADED_ART_TILES) {
                // Cache is full — make room by evicting something that's currently off-screen
                // rather than refusing to load anything new. Without this, once you've panned
                // across enough of the square to fill the cache, newly-visible areas would never
                // get their art loaded at all.
                val evictable = albumArtCache.keys.firstOrNull { it !in visibleIds }
                if (evictable != null) {
                    albumArtCache.remove(evictable)
                } else {
                    break // everything cached is currently on-screen; nothing safe to evict yet
                }
            }
            inFlightArtIds += id
            artScope.launch(Dispatchers.IO) {
                artLoadSemaphore.withPermit {
                    val bitmap = loadSmallAlbumArt(context, point.song.albumArtUriString)
                    if (bitmap != null) albumArtCache[id] = bitmap else failedArtIds += id
                }
                inFlightArtIds -= id
            }
        }
    }

    // `progress` can briefly report total=0 right when a no-op re-check kicks off (e.g. every
    // time you swipe back to this tab, even if analysis finished ages ago) — guarding on
    // total > 0 as well as analyzed < total keeps that from flashing an "0 / 0" banner.
    val hasOutstandingAnalysis = progress.total > 0 && progress.analyzed < progress.total
    val showAnalyzingBanner = hasOutstandingAnalysis && progress.isRunning

    fun updateProbe(normalizedOffset: Offset) {
        probe = normalizedOffset
        nearestPreview = moodPoints
            .sortedBy { point ->
                val dx = point.x - normalizedOffset.x
                val dy = point.y - normalizedOffset.y
                hypot(dx.toDouble(), dy.toDouble())
            }
            .take(neighborCount)
    }

    // Drops a probe at a randomized point within the given quadrant (inset from its own edges
    // by 18%, same fraction the desktop tool uses, so it lands somewhere representative of that
    // mood rather than right on the pad's border or dead center every time) and immediately
    // plays a mix built from it — same mechanism double-tapping the board directly uses, so
    // re-picking the same mood gives a different mix each time instead of a fixed spot.
    fun moodShuffle(x0: Float, x1: Float, y0: Float, y1: Float) {
        val insetX = 0.18f * (x1 - x0)
        val insetY = 0.18f * (y1 - y0)
        val rx = x0 + insetX + Random.nextFloat() * ((x1 - x0) - insetX * 2f)
        val ry = y0 + insetY + Random.nextFloat() * ((y1 - y0) - insetY * 2f)
        updateProbe(Offset(rx, ry))
        val neighbors = nearestPreview
        if (neighbors.isNotEmpty()) {
            playerViewModel.showAndPlaySong(
                neighbors.first().song,
                neighbors.map { it.song },
                "Music Square"
            )
        }
    }

    // Search filter: purely a visual "spotlight" on the board (matching dots drawn at full
    // opacity, everything else dimmed) rather than removing anything — mixes/probes still draw
    // from the whole square regardless of what's currently searched.
    var searchQuery by remember { mutableStateOf("") }
    val filterActive = searchQuery.isNotBlank()
    val matchedIds = remember(searchQuery, moodPoints) {
        if (searchQuery.isBlank()) {
            emptySet()
        } else {
            moodPoints.filter {
                it.song.title.contains(searchQuery, ignoreCase = true) ||
                    it.song.artist.contains(searchQuery, ignoreCase = true)
            }.mapTo(mutableSetOf()) { it.song.id }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showAnalyzingBanner) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.music_square_analyzing_progress,
                        progress.analyzed,
                        progress.total
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(50)),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var showMoodMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showMoodMenu = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.music_square_mood_shuffle),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                DropdownMenu(expanded = showMoodMenu, onDismissRequest = { showMoodMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.music_square_mood_mellow))
                                Text(
                                    stringResource(R.string.music_square_mood_mellow_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMoodMenu = false
                            moodShuffle(x0 = 0f, x1 = 0.5f, y0 = 0.5f, y1 = 1f)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.music_square_mood_upbeat))
                                Text(
                                    stringResource(R.string.music_square_mood_upbeat_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMoodMenu = false
                            moodShuffle(x0 = 0.5f, x1 = 1f, y0 = 0.5f, y1 = 1f)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.music_square_mood_moody))
                                Text(
                                    stringResource(R.string.music_square_mood_moody_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMoodMenu = false
                            moodShuffle(x0 = 0f, x1 = 0.5f, y0 = 0f, y1 = 0.5f)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.music_square_mood_intense))
                                Text(
                                    stringResource(R.string.music_square_mood_intense_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showMoodMenu = false
                            moodShuffle(x0 = 0.5f, x1 = 1f, y0 = 0f, y1 = 0.5f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                placeholder = {
                    Text(
                        text = stringResource(R.string.music_square_search_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.music_square_search_cd_clear),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(50)
            )
        }

        Text(
            text = stringResource(R.string.music_square_axis_y_joyful),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // This Row gets whatever vertical space is left after everything else in the Column
        // (the banner/label above, the instructions/spacer below), and the board itself sizes
        // to fillMaxHeight + aspectRatio(matchHeightConstraintsFirst = true) — i.e. it's
        // exactly as tall as that leftover space, with width following to keep it square. That
        // means the board is bounded by whichever of width/height is smaller, rather than a
        // fixed fraction of just the width, so it fills noticeably more of the screen on most
        // phones (which have more height to spare than width).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotatedAxisLabel(
                text = stringResource(R.string.music_square_axis_x_calm),
                rotateClockwise = false,
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .onSizeChanged { squareSizePx = it }
                    // One finger (or two, for pinch) pans/zooms the square. This is a single
                    // built-in detector rather than something custom: detectTransformGestures
                    // reports a pan delta and a zoom factor for however many pointers are down,
                    // so a plain one-finger drag naturally comes through as "pan, zoom=1" and a
                    // two-finger pinch comes through as "pan+zoom" — no manual finger-count
                    // branching needed like the probe-vs-pinch logic this replaced.
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_SQUARE_ZOOM)
                            // Keep the content point currently under the pinch centroid fixed
                            // under that centroid as zoom changes, then also apply the raw
                            // pan delta on top (this is what makes a plain one-finger drag —
                            // where zoomChange is always 1f — reduce to simple pan + panChange,
                            // same as before; only the zoom case changes anchor).
                            val newPan = centroid + panChange - (centroid - pan) * (newZoom / zoom)
                            val minPanX = size.width * (1f - newZoom)
                            val minPanY = size.height * (1f - newZoom)
                            zoom = newZoom
                            pan = Offset(
                                newPan.x.coerceIn(minPanX, 0f),
                                newPan.y.coerceIn(minPanY, 0f)
                            )
                        }
                    }
                    // Double-tap drops a probe at that spot and immediately starts playing a mix
                    // built from the nearest-sounding tracks there.
                    .pointerInput(moodPoints, zoom, pan) {
                        val boxSize = size
                        detectTapGestures(
                            onDoubleTap = { rawPosition ->
                                // Raw touch -> "content space" (the square's own un-zoomed
                                // 0..w,0..h coordinate system MoodSquareCanvas draws in) by
                                // inverting the pan/zoom affine transform, then -> normalized
                                // 0..1 mood coordinates.
                                val contentX = (rawPosition.x - pan.x) / zoom
                                val contentY = (rawPosition.y - pan.y) / zoom
                                val nx = (contentX / boxSize.width.toFloat()).coerceIn(0f, 1f)
                                // Canvas y grows downward; mood-space y grows upward (sad->joyful).
                                val ny = (1f - contentY / boxSize.height.toFloat()).coerceIn(0f, 1f)
                                updateProbe(Offset(nx, ny))

                                val neighbors = nearestPreview
                                if (neighbors.isNotEmpty()) {
                                    playerViewModel.showAndPlaySong(
                                        neighbors.first().song,
                                        neighbors.map { it.song },
                                        "Music Square"
                                    )
                                }
                            }
                        )
                    }
            ) {
                MoodSquareCanvas(
                    moodPoints = moodPoints,
                    probe = probe,
                    highlightedIds = nearestPreview.map { it.song.id }.toSet(),
                    zoom = zoom,
                    pan = pan,
                    albumArtCache = albumArtCache,
                    filterActive = filterActive,
                    matchedIds = matchedIds
                )
                CornerLabel(text = "Mellow", alignment = Alignment.TopStart)
                CornerLabel(text = "Upbeat", alignment = Alignment.TopEnd)
                CornerLabel(text = "Moody", alignment = Alignment.BottomStart)
                CornerLabel(text = "Intense", alignment = Alignment.BottomEnd)

                if (zoom > 1.01f) {
                    IconButton(
                        onClick = {
                            zoom = 1f
                            pan = Offset.Zero
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(32.dp)
                            .background(
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reset zoom",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            RotatedAxisLabel(
                text = stringResource(R.string.music_square_axis_x_exciting),
                rotateClockwise = true,
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
            )
        }

        Text(
            text = stringResource(R.string.music_square_axis_y_sad),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        Text(
            text = "Double-tap anywhere to build a mix from that spot. Drag to look around, pinch to zoom in on the art.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Extra clearance beneath the mini-player + bottom nav bar — same formula the other
        // library tabs use for their list content padding.
        Spacer(modifier = Modifier.height(bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap))
    }
}

/**
 * A single-line label rotated 90 degrees for the square's left/right edges. Plain
 * `Modifier.rotate()` only rotates the drawing, not the measured bounds, so a naive rotated
 * Text would still reserve its full unrotated width and barely fit in a narrow side column;
 * this swaps the measured width/height so the reserved space matches what you see.
 */
@Composable
private fun RotatedAxisLabel(
    text: String,
    modifier: Modifier = Modifier,
    rotateClockwise: Boolean,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints())
                    layout(placeable.height, placeable.width) {
                        placeable.place(
                            x = -(placeable.width - placeable.height) / 2,
                            y = -(placeable.height - placeable.width) / 2
                        )
                    }
                }
                .graphicsLayer { rotationZ = if (rotateClockwise) 90f else -90f }
        )
    }
}

/**
 * Caps how many distinct album art bitmaps stay decoded at once. Now that art loads for the
 * whole visible square regardless of zoom (not just once zoomed in), a large library can ask
 * for thousands of thumbnails at once — this bounds memory, with off-screen entries evicted
 * first to make room once you zoom/pan into new territory. Decoded at a smaller size (64x64
 * instead of the previous 96x96) specifically to keep a big cache affordable in memory; that's
 * a deliberate quality/memory tradeoff — thumbnails will look a little softer at high zoom than
 * a full 96x96 decode would, in exchange for being able to hold ~2-3x as many at once.
 */
private const val MAX_LOADED_ART_TILES = 2000
private const val ART_TILE_DECODE_SIZE = 64

private const val MAX_SQUARE_ZOOM = 6f

/** Decodes a small thumbnail for Canvas drawing (not a Composable — this is for direct `drawImage` calls, not `AsyncImage`). Mirrors the pattern in [com.theveloper.pixelplay.data.service.CoilBitmapLoader]. */
private suspend fun loadSmallAlbumArt(context: Context, albumArtUriString: String?): ImageBitmap? {
    if (albumArtUriString.isNullOrBlank()) return null
    return try {
        val request = ImageRequest.Builder(context)
            .data(albumArtUriString)
            .size(ART_TILE_DECODE_SIZE, ART_TILE_DECODE_SIZE)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        result.drawable?.toBitmap()?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}

@Composable
private fun MoodSquareCanvas(
    moodPoints: List<MoodPoint>,
    probe: Offset?,
    highlightedIds: Set<String>,
    zoom: Float,
    pan: Offset,
    albumArtCache: Map<String, ImageBitmap>,
    filterActive: Boolean = false,
    matchedIds: Set<String> = emptySet()
) {
    val probeColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val highlightRingColor = MaterialTheme.colorScheme.onSurface

    // Four fixed anchor colors, one per corner/mood — mellow (calm+joyful), upbeat
    // (exciting+joyful), moody (calm+sad), intense (exciting+sad) — bilinearly blended per
    // point below so a song's dot color reflects exactly where it sits on the square, the same
    // way the desktop tool colors its grid.
    val mellowColor = Color(0xFF5FBFAE)
    val upbeatColor = Color(0xFFE0B25C)
    val moodyColor = Color(0xFF7B7FD1)
    val intenseColor = Color(0xFFE06C8C)

    fun moodColor(x: Float, y: Float): Color {
        val top = lerp(mellowColor, upbeatColor, x)
        val bottom = lerp(moodyColor, intenseColor, x)
        return lerp(bottom, top, y)
    }

    // Art is preferred at any zoom level now — the colored dot is just the fallback for songs
    // with no embedded art, or whose art hasn't finished decoding yet.
    //
    // Divided by `zoom`: this Canvas is scaled up by the outer graphicsLayer(scaleX = zoom, ...)
    // as you zoom in, so a plain fixed value here would get magnified right along with the
    // increased spacing between points — meaning two overlapping dots stay exactly as
    // overlapping (relative to their own size) at any zoom level, since it's just a bigger
    // picture of the same crowding. Dividing by zoom cancels that scale-up so the artwork keeps
    // a constant *screen* size while the spacing between points keeps growing — which is what
    // actually lets zooming in de-clutter a crowded area, matching the desktop tool's pad
    // (whose dots/art are drawn at a fixed pixel radius regardless of its own zoom level).
    val artHalfExtent = 9f / zoom

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // Pin scaling to the top-left corner so pan/zoom is a simple affine transform
            // (screen = content * zoom + pan) — matches the inverse used for tap hit-testing
            // in the pointerInput above.
            .graphicsLayer(
                scaleX = zoom,
                scaleY = zoom,
                translationX = pan.x,
                translationY = pan.y,
                transformOrigin = TransformOrigin(0f, 0f)
            )
    ) {
        val w = size.width
        val h = size.height

        // Faint center crosshair for orientation. Stroke width divided by zoom for the same
        // reason artHalfExtent is above — otherwise the line reads as thicker the further in
        // you zoom, purely from the outer graphicsLayer's scale-up.
        drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.5f / zoom)
        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.5f / zoom)

        for (point in moodPoints) {
            val cx = point.x * w
            val cy = (1f - point.y) * h
            val isHighlighted = point.song.id in highlightedIds
            val art = albumArtCache[point.song.id]
            // Search filter is a visual "spotlight", not a removal — non-matching tracks dim to
            // 0.14 alpha (same value the desktop tool's own filter dim uses) rather than
            // disappearing, so the whole square stays visible for context.
            val dimmed = filterActive && point.song.id !in matchedIds
            val filterAlpha = if (dimmed) 0.14f else 1f

            if (art != null) {
                val extent = if (isHighlighted) artHalfExtent * 1.3f else artHalfExtent
                // Clip the square thumbnail to a circle so tiles read as round dots on the
                // square, matching the desktop tool's circular tiles, instead of little squares.
                val artBounds = Rect(
                    left = cx - extent,
                    top = cy - extent,
                    right = cx + extent,
                    bottom = cy + extent
                )
                clipPath(Path().apply { addOval(artBounds) }) {
                    drawImage(
                        image = art,
                        dstOffset = IntOffset(artBounds.left.toInt(), artBounds.top.toInt()),
                        dstSize = IntSize(artBounds.width.toInt(), artBounds.height.toInt()),
                        alpha = filterAlpha
                    )
                }
                if (isHighlighted) {
                    drawCircle(
                        color = highlightRingColor,
                        radius = extent,
                        center = Offset(cx, cy),
                        alpha = filterAlpha,
                        style = Stroke(width = 1.5f / zoom)
                    )
                }
            } else {
                val color = moodColor(point.x, point.y)
                // Every point here is a real audio-analyzed song (MoodRepository never emits a
                // genre-only fallback position in this build), so there's no reduced-trust case
                // to dim for beyond the search filter above.
                val baseAlpha = 0.9f

                drawCircle(
                    color = color,
                    radius = (if (isHighlighted) 6.5f else 4.5f) / zoom,
                    center = Offset(cx, cy),
                    alpha = filterAlpha * (if (isHighlighted) 1f else baseAlpha)
                )
                if (isHighlighted) {
                    drawCircle(
                        color = highlightRingColor,
                        radius = 9f / zoom,
                        center = Offset(cx, cy),
                        alpha = filterAlpha * 0.9f,
                        style = Stroke(width = 1.5f / zoom)
                    )
                }
            }
        }

        probe?.let { p ->
            val cx = p.x * w
            val cy = (1f - p.y) * h
            drawCircle(color = probeColor.copy(alpha = 0.25f), radius = 42f / zoom, center = Offset(cx, cy))
            drawCircle(color = probeColor, radius = 10f / zoom, center = Offset(cx, cy))
        }
    }
}

/** A small caption label pinned inside one corner of the square (Mellow/Upbeat/Moody/Intense). */
@Composable
private fun BoxScope.CornerLabel(text: String, alignment: Alignment) {
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .align(alignment)
            .padding(6.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MusicSquareWelcomeDialog(
    onImportClick: () -> Unit,
    onAnalyzeLocallyClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Must resolve via a choice below — nothing to fall back to yet. */ },
        title = { Text(stringResource(R.string.music_square_welcome_title)) },
        text = { Text(stringResource(R.string.music_square_welcome_subtitle)) },
        confirmButton = {
            Button(onClick = onAnalyzeLocallyClick) {
                Text(stringResource(R.string.music_square_welcome_analyze))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onImportClick) {
                Text(stringResource(R.string.music_square_welcome_import))
            }
        }
    )
}

@Composable
private fun MusicSquareSnippetChoiceDialog(
    initialSnippetSeconds: Int = MoodAnalyzer.DEFAULT_SNIPPET_SECONDS,
    initialOffsetPercent: Int = 0,
    onSelect: (seconds: Int, offsetPercent: Int) -> Unit,
    onBack: () -> Unit
) {
    val snippetOptions = listOf(
        15 to R.string.music_square_snippet_15s,
        MoodAnalyzer.DEFAULT_SNIPPET_SECONDS to R.string.music_square_snippet_25s,
        45 to R.string.music_square_snippet_45s,
        MoodAnalyzer.FULL_TRACK to R.string.music_square_snippet_full
    )
    val offsetOptions = listOf(
        0 to R.string.music_square_offset_0,
        10 to R.string.music_square_offset_10,
        30 to R.string.music_square_offset_30,
        50 to R.string.music_square_offset_50
    )
    var selectedSeconds by remember { mutableStateOf(initialSnippetSeconds) }
    var selectedOffsetPercent by remember { mutableStateOf(initialOffsetPercent) }
    // A start offset only makes sense when sampling a snippet — analyzing the full track
    // already covers everything from 0s, so there's nothing for "how far in" to mean there.
    val offsetApplies = selectedSeconds != MoodAnalyzer.FULL_TRACK

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.music_square_snippet_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.music_square_snippet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                snippetOptions.forEach { (seconds, labelRes) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedSeconds = seconds }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selectedSeconds == seconds, onClick = { selectedSeconds = seconds })
                        Text(stringResource(labelRes))
                    }
                }

                AnimatedVisibility(visible = offsetApplies, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        Text(
                            text = stringResource(R.string.music_square_offset_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                        Text(
                            text = stringResource(R.string.music_square_offset_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        offsetOptions.forEach { (percent, labelRes) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedOffsetPercent = percent }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected = selectedOffsetPercent == percent,
                                    onClick = { selectedOffsetPercent = percent }
                                )
                                Text(stringResource(labelRes))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(selectedSeconds, if (offsetApplies) selectedOffsetPercent else 0) }) {
                Text(stringResource(R.string.music_square_welcome_analyze))
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.music_square_snippet_back))
            }
        }
    )
}

/**
 * The Music Square's settings — opened from the cog next to the "Joyful" axis label. Mirrors
 * the desktop tool's own settings panel in spirit: lets you re-run local analysis with
 * different sampling settings, (re-)import a desktop-tool export, or export this device's
 * analysis back out in the same format.
 */
@Composable
private fun MusicSquareSettingsDialog(
    onImportClick: () -> Unit,
    onAnalyzeLocallyClick: () -> Unit,
    onExportClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.music_square_settings_title)) },
        text = {
            Column {
                MusicSquareSettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = stringResource(R.string.music_square_settings_analyze),
                    subtitle = stringResource(R.string.music_square_settings_analyze_subtitle),
                    onClick = onAnalyzeLocallyClick
                )
                MusicSquareSettingsRow(
                    icon = Icons.Rounded.FileUpload,
                    title = stringResource(R.string.music_square_settings_import),
                    subtitle = stringResource(R.string.music_square_settings_import_subtitle),
                    onClick = onImportClick
                )
                MusicSquareSettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(R.string.music_square_settings_export),
                    subtitle = stringResource(R.string.music_square_settings_export_subtitle),
                    onClick = onExportClick
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.music_square_settings_close))
            }
        }
    )
}

@Composable
private fun MusicSquareSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MusicSquareEmptyState(
    isAnalyzing: Boolean,
    analyzed: Int,
    total: Int,
    onAnalyzeClick: () -> Unit,
    bottomBarHeight: Dp
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomBarHeight),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_grid_view_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.music_square_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = stringResource(R.string.music_square_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            if (isAnalyzing) {
                Text(
                    text = stringResource(R.string.music_square_analyzing_progress, analyzed, total),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                LinearProgressIndicator(
                    progress = { if (total > 0) (analyzed.toFloat() / total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                )
            } else {
                Button(
                    onClick = onAnalyzeClick,
                    modifier = Modifier.padding(top = 20.dp)
                ) {
                    Text(stringResource(R.string.music_square_analyze_button))
                }
            }
        }
    }
}
