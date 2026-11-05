@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.daymark.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun CloudPanel(state: CloudUi, model: DaymarkViewModel, dismiss: () -> Unit) {
    var endpoint by rememberSaveable(state.url) { mutableStateOf(state.url) }
    var publicKey by rememberSaveable(state.key) { mutableStateOf(state.key) }
    var email by rememberSaveable { mutableStateOf(state.email) }
    // Passwords intentionally do not survive process restoration or leaving this sheet.
    var password by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var importConfirm by remember { mutableStateOf(false) }
    var removeId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.listWorkspaces() }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp)
            .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shared lists", style = MaterialTheme.typography.headlineMedium)
            Text("Create a list, invite someone, and edit together. Shared changes need an internet connection; saved data is available offline.", color = Muted)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = Forest) }
            if (!state.signedIn) {
                Text("Connect your project", style = MaterialTheme.typography.titleMedium)
                Text("Use a Supabase Free project with the Daymark setup SQL installed. Everyone joining a list uses the same project URL and public key.", color = Muted)
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Supabase project URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedTextField(publicKey, { publicKey = it }, label = { Text("Publishable / anon key") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedButton(onClick = { model.configureCloud(endpoint, publicKey) }, enabled = !state.busy && endpoint.isNotBlank() && publicKey.isNotBlank()) { Text("Save project") }
                HorizontalDivider(color = Line)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Button(onClick = { model.authenticate(email, password, false); password = "" }, enabled = !state.busy && state.url.isNotBlank() && email.isNotBlank() && password.isNotEmpty()) { Text("Sign in") }
                OutlinedButton(onClick = { model.authenticate(email, password, true); password = "" }, enabled = !state.busy && state.url.isNotBlank() && email.isNotBlank() && password.length >= 8) { Text("Create account") }
                Text("Use at least 8 characters. Confirm your email before signing in if your project requires it.", color = Muted)
            } else {
                Text(state.email, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = model::signOut, enabled = !state.busy) { Text("Sign out") }
                state.workspace?.let { w ->
                    HorizontalDivider(color = Line)
                    Text(w.name, style = MaterialTheme.typography.titleLarge)
                    Text("Time zone: ${w.zone}", color = Muted)
                    Text(state.syncStatus, color = Muted)
                    if (state.pending) {
                        Text("A save did not receive confirmation. Retry it before making another change.", color = Clay)
                        Button(onClick = model::retryPending, enabled = !state.busy) { Text("Retry pending save") }
                    }
                    OutlinedButton(onClick = { importConfirm = true }, enabled = !state.busy && !state.pending) { Text("Import original local data") }
                    if (state.owner) {
                        OutlinedButton(onClick = { model.showInvite() }, enabled = !state.busy) { Text("Show invitation and members") }
                        state.inviteCode?.let { invitation ->
                            SelectionContainer { Text(invitation, style = MaterialTheme.typography.bodyMedium) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("Daymark invitation", invitation))
                                }) { Text("Copy code") }
                                TextButton(onClick = {
                                    val text = "Join my Daymark shared list: ${w.name}\nProject URL: ${state.url}\nPublic key: ${state.key}\nInvitation code: $invitation\nIn Daymark, open Shared lists, save the project, sign in, then join with this code."
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Daymark invitation"))
                                }) { Text("Share invitation") }
                            }
                            TextButton(onClick = { model.showInvite(true) }, enabled = !state.busy) { Text("Replace invitation code") }
                            Text("Anyone with the code can join. Replacing it prevents old codes being reused; existing members stay.", color = Muted)
                            state.members.forEach { id ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(id, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    if (id != w.owner) TextButton(onClick = { removeId = id }, enabled = !state.busy) { Text("Remove") }
                                    else Text("Owner", color = Muted)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Text("Your lists", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = model::listWorkspaces, enabled = !state.busy) { Text("Refresh lists") }
                state.workspaces.forEach { w ->
                    OutlinedButton(onClick = { model.selectWorkspace(w) }, enabled = !state.busy && !state.pending && state.workspace?.id != w.id, modifier = Modifier.fillMaxWidth()) { Text(w.name) }
                }
                TextButton(onClick = { model.selectWorkspace(null) }, enabled = !state.busy && !state.pending && state.workspace != null) { Text("Use original local data") }
                OutlinedTextField(name, { name = it }, label = { Text("New list name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Button(onClick = { model.createWorkspace(name) }, enabled = !state.busy && !state.pending && name.isNotBlank()) { Text("Create shared list") }
                OutlinedTextField(code, { code = it }, label = { Text("Invitation code") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedButton(onClick = { model.joinWorkspace(code) }, enabled = !state.busy && !state.pending && code.isNotBlank()) { Text("Join shared list") }
            }
        }
    }
    if (importConfirm) AlertDialog(onDismissRequest = { importConfirm = false }, title = { Text("Import local data?") },
        text = { Text("Upload all original local tasks, history, and events into ${state.workspace?.name}. Every member will be able to read and edit them. Your original local data stays on this phone. This import runs once per list.") },
        confirmButton = { TextButton(onClick = { importConfirm = false; model.importLocal() }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { importConfirm = false }) { Text("Cancel") } })
    removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Remove member?") },
        text = { Text("This person will lose cloud access to this list. Data already saved on their phone cannot be remotely erased. Replace the invitation code too if they know it.") },
        confirmButton = { TextButton(onClick = { removeId = null; model.removeMember(id) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } }) }
}
