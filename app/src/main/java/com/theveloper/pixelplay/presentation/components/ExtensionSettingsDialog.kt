package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.extension.api.ExtensionSetting

/** Generic settings form for any extension that declares [ExtensionSetting]s. */
@Composable
fun ExtensionSettingsDialog(
    title: String,
    settings: List<ExtensionSetting>,
    initialValues: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(initialValues) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                settings.forEach { s ->
                    OutlinedTextField(
                        value = values[s.key].orEmpty(),
                        onValueChange = { values[s.key] = it },
                        label = { Text(s.label) },
                        supportingText = s.hint?.let { hint -> { Text(hint) } },
                        singleLine = true,
                        visualTransformation = if (s.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (s.isSecret) KeyboardType.Password else KeyboardType.Text
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(values.toMap()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
