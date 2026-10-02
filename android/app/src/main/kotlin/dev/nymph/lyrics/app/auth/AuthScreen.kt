package dev.nymph.lyrics.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nymph.lyrics.app.di.AppContainer
import dev.nymph.lyrics.app.ui.theme.Palette

@Composable
fun AuthScreen(container: AppContainer) {
    val vm: AuthViewModel = viewModel { AuthViewModel(container.auth) }
    val state by vm.state.collectAsStateWithLifecycle()
    val register = state.mode == AuthMode.REGISTER
    Scaffold(containerColor = Palette.CanvasBackground) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Lyrics Canvas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Row {
                TextButton(onClick = { vm.setMode(AuthMode.LOGIN) }, enabled = register) { Text("Sign in") }
                TextButton(onClick = { vm.setMode(AuthMode.REGISTER) }, enabled = !register) { Text("Register") }
            }
            val fieldModifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)
            if (register) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = vm::setDisplayName,
                    label = { Text("Display name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = fieldModifier,
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::setEmail,
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = fieldModifier,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::setPassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                modifier = fieldModifier,
            )
            val error = when {
                state.fieldError == AuthFieldError.MISSING_FIELDS -> if (register) "Fill in name, email and password." else "Fill in email and password."
                else -> state.error
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = Palette.Error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::submit, enabled = !state.submitting, modifier = fieldModifier) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (register) "Create account" else "Sign in")
                }
            }
        }
    }
}
