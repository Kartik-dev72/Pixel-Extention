package com.theveloper.pixelplay.presentation.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.extension.LoadedExtension
import com.theveloper.pixelplay.presentation.components.ExtensionSettingsDialog
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    viewModel: ExtensionsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onOpenDiscover: () -> Unit = {}
) {
    val context = LocalContext.current
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importEvent by viewModel.importEvent.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var pendingRemoval by remember { mutableStateOf<LoadedExtension?>(null) }
    var editingSettings by remember { mutableStateOf<LoadedExtension?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        var displayName = uri.lastPathSegment ?: "extension.apk"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                displayName = cursor.getString(nameIndex)
            }
        }

        viewModel.importExtension(uri, displayName)
    }

    LaunchedEffect(importEvent) {
        val event = importEvent ?: return@LaunchedEffect
        val message = when (event) {
            is ExtensionImportEvent.Success ->
                context.getString(R.string.extensions_import_success, event.displayName)
            is ExtensionImportEvent.Failed ->
                context.getString(R.string.extensions_import_failed, event.message)
        }
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        viewModel.clearImportEvent()
    }

    pendingRemoval?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.extensions_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.extensions_remove_confirm_body,
                        extension.metadata.displayName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeExtension(extension.filePath)
                    pendingRemoval = null
                }) {
                    Text(
                        stringResource(R.string.extensions_remove_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    editingSettings?.let { extension ->
        val id = extension.metadata.id
        val defs = remember(id) { viewModel.settingsFor(id) }
        val initial = remember(id) { defs.associate { it.key to viewModel.getSetting(id, it.key) } }
        ExtensionSettingsDialog(
            title = "${extension.metadata.displayName} settings",
            settings = defs,
            initialValues = initial,
            onSave = { values ->
                viewModel.saveSettings(id, values)
                editingSettings = null
            },
            onDismiss = { editingSettings = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.extensions_screen_title),
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
                    if (extensions.any { it.isEnabled }) {
                        FilledTonalIconButton(
                            onClick = onOpenDiscover,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Explore,
                                contentDescription = stringResource(R.string.discover_screen_title)
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { importLauncher.launch("*/*") },
                        enabled = !isImporting,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.extensions_add_button))
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { padding ->
        if (extensions.isEmpty()) {
            EmptyExtensionsState(
                onAddClick = { importLauncher.launch("*/*") },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(extensions, key = { it.metadata.id }) { extension ->
                    val hasSettings = viewModel.settingsFor(extension.metadata.id).isNotEmpty()
                    ExtensionCard(
                        extension = extension,
                        onToggleEnabled = { enabled -> viewModel.setEnabled(extension.metadata.id, enabled) },
                        onRemove = { pendingRemoval = extension },
                        onOpenSettings = if (hasSettings) ({ editingSettings = extension }) else null
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: LoadedExtension,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.metadata.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.extensions_by_author,
                        extension.metadata.author,
                        extension.metadata.version
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (extension.metadata.description.isNotBlank()) {
                    Text(
                        text = extension.metadata.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (onOpenSettings != null) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Settings")
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = extension.isEnabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun EmptyExtensionsState(
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.extensions_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.extensions_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalIconButton(onClick = onAddClick) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.extensions_add_button))
            }
        }
    }
}
