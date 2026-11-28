package org.aurral.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.aurral.android.Account
import org.aurral.android.AurralApp
import org.aurral.android.api.SubsonicClient
import org.aurral.android.api.SubsonicException
import org.aurral.android.sync.SyncScheduler
import java.util.UUID

private fun normalize(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.contains("://")) return trimmed
    return (if (trimmed.firstOrNull()?.isDigit() == true || trimmed.startsWith("localhost")) "http://" else "https://") + trimmed
}

/** Tries token sign-in first, then password sign-in for accounts without a stored Subsonic password. */
private suspend fun signIn(url: String, username: String, password: String): Account {
    val app = AurralApp.instance
    val base = Account(normalize(url), username.trim(), password, UUID.randomUUID().toString().take(12), passwordAuth = false)
    try {
        SubsonicClient(base, app.http).ping()
        return base
    } catch (e: SubsonicException) {
        if (e.code != 41) throw e
    }
    val legacy = base.copy(passwordAuth = true)
    SubsonicClient(legacy, app.http).ping()
    return legacy
}

@Composable
fun SignInScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val app = AurralApp.instance
    val existing = app.accounts.state.value
    var url by remember { mutableStateOf(existing?.serverUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Glass.page)) {
        if (onBack != null) GlassTopBar("Server and account", onBack = onBack) else Spacer(Modifier.statusBarsPadding())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
        ) {
            if (onBack == null) {
                Text("Aurral", style = MaterialTheme.typography.displaySmall, color = Glass.Sky)
                Text("Sign in to your server to sync and play your music.", color = Glass.Muted)
                Spacer(Modifier.height(24.dp))
            }
            OutlinedTextField(
                url,
                { url = it },
                Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://music.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                password,
                { password = it },
                Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            app.accounts.save(signIn(url, username, password))
                            SyncScheduler.applySchedule(app)
                            onDone()
                        } catch (e: SubsonicException) {
                            error = if (e.code == 40) "Wrong username or password" else e.message
                        } catch (e: Exception) {
                            error = "Can't reach that address: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Checking…" else "Sign in") }
            Spacer(Modifier.height(16.dp))
            Text(
                "Uses Aurral's Subsonic access at /rest. Behind a sign-in proxy such as Authentik, let /rest/* " +
                    "through without the proxy login; Aurral still checks your password. An account created by " +
                    "the proxy needs a password set in Aurral first.",
                style = MaterialTheme.typography.bodySmall,
                color = Glass.Muted,
            )
            if (onBack != null && existing != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = { app.accounts.signOut(); SyncScheduler.applySchedule(app) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
                Text(
                    "Music already on this phone stays and keeps playing.",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.Muted,
                )
            }
        }
    }
}
