@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.daymark.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun CloudPanel(state: CloudUi, model: DaymarkViewModel, dismiss: () -> Unit) {
    var importConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shared list", style = MaterialTheme.typography.headlineMedium)
            Text(state.workspace?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            Text(state.syncStatus, color = Muted)
            Text("Time zone: ${state.workspace?.zone.orEmpty()}", color = Muted)
            Text("Changes are shared with everyone using the shared password. Saved data is available offline.", color = Muted)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = Forest) }
            if (state.pending) {
                Text("A save did not receive confirmation. Retry it before making another change.", color = Clay)
                Button(onClick = model::retryPending, enabled = !state.busy) { Text("Retry pending save") }
            }
            OutlinedButton(onClick = { importConfirm = true }, enabled = !state.busy && !state.pending) { Text("Import original local data") }
            TextButton(onClick = model::signOut, enabled = !state.busy) { Text("Sign out") }
        }
    }
    if (importConfirm) AlertDialog(onDismissRequest = { importConfirm = false }, title = { Text("Import local data?") },
        text = { Text("Upload all original local tasks, history, and events into this shared list. Everyone using the shared password can read and edit them. Your original local data stays on this phone. This import runs once per list.") },
        confirmButton = { TextButton(onClick = { importConfirm = false; model.importLocal() }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { importConfirm = false }) { Text("Cancel") } })
}
