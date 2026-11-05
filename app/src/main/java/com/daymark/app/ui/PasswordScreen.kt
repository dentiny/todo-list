package com.daymark.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun PasswordScreen(state: CloudUi, signIn: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val submit = {
        if (password.isNotBlank() && !state.busy) {
            signIn(password)
            password = ""
            passwordVisible = false
        }
    }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Text("DAYMARK", color = Forest, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(24.dp))
            Text("Your shared space.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("Enter the shared password to open your tasks and events.", color = Muted)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(password, { password = it }, label = { Text("Shared password") },
                singleLine = true, enabled = !state.busy,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = if (passwordVisible) "Hide password" else "Show password" }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth())
            state.message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Clay) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = submit, enabled = password.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.busy) "Connecting…" else "Open shared list")
            }
            Spacer(Modifier.height(12.dp))
            Text("You'll stay signed in until you sign out.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
