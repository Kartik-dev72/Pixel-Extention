package com.theveloper.pixelplay.presentation.extensions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.extension.ExtensionRepository
import com.theveloper.pixelplay.data.extension.ExtensionV2Repository
import com.theveloper.pixelplay.data.extension.ImportResult
import com.theveloper.pixelplay.data.extension.LoadedExtension
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ExtensionImportEvent {
    data class Success(val displayName: String) : ExtensionImportEvent()
    data class Failed(val message: String) : ExtensionImportEvent()
}

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val repository: ExtensionRepository,
    private val v2: ExtensionV2Repository
) : ViewModel() {

    fun settingsFor(id: String) = v2.settingsFor(id)
    fun getSetting(id: String, key: String) = v2.getSetting(id, key).orEmpty()
    fun saveSettings(id: String, values: Map<String, String>) =
        values.forEach { (k, v) -> v2.setSetting(id, k, v.trim().ifBlank { null }) }

    val extensions: StateFlow<List<LoadedExtension>> = repository.extensions
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importEvent = MutableStateFlow<ExtensionImportEvent?>(null)
    val importEvent: StateFlow<ExtensionImportEvent?> = _importEvent.asStateFlow()

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun importExtension(uri: Uri, suggestedFileName: String) {
        viewModelScope.launch {
            _isImporting.value = true
            val result = repository.importExtension(uri, suggestedFileName)
            _importEvent.value = when (result) {
                is ImportResult.Success -> ExtensionImportEvent.Success(result.displayName)
                is ImportResult.Failed -> ExtensionImportEvent.Failed(result.message)
            }
            _isImporting.value = false
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
    }

    fun removeExtension(filePath: String) {
        repository.removeExtension(filePath)
    }

    fun clearImportEvent() {
        _importEvent.value = null
    }
}
