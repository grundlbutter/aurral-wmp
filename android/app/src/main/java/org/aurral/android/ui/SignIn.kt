package org.aurral.android.ui

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.aurral.android.Account
import org.aurral.android.AurralApp
import org.aurral.android.api.SubsonicClient
import org.aurral.android.api.SubsonicException
import org.aurral.android.sync.SyncScheduler
import java.security.SecureRandom
import java.util.UUID

private fun normalize(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    if (trimmed.contains("://")) return trimmed
    return (if (trimmed.firstOrNull()?.isDigit() == true || trimmed.startsWith("localhost")) "http://" else "https://") + trimmed
}

private fun newSalt() = UUID.randomUUID().toString().take(12)

/** Tries token sign-in first, then password sign-in for accounts without a stored Subsonic password. */
private suspend fun signIn(url: String, username: String, password: String, apiKey: String): Account {
    val app = AurralApp.instance
    val base = Account(normalize(url), username.trim(), password, newSalt(), passwordAuth = false, apiKey = apiKey.trim())
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

private fun describe(e: Exception) = when ((e as? SubsonicException)?.code) {
    40 -> "Wrong username or password"
    44 -> "Aurral didn't accept that key. Try connecting again."
    50 -> "This server needs your app key: in Aurral, Settings > Profile > API access"
    null -> if (e is SubsonicException) e.message else "Can't reach that address: ${e.message}"
    else -> e.message
}

/**
 * Browser sign-in: the app opens /connect-app on the server, where the user signs in however the server
 * (or the proxy in front of it) asks, and the page hands back their API key on aurral://connect.
 */
object BrowserLink {
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val prefs get() = AurralApp.instance.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun start(context: Context, address: String) {
        val server = normalize(address)
        if (server.toHttpUrlOrNull() == null) {
            error.value = "That address doesn't look right"
            return
        }
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString("server", server).putString("state", state).apply()
        error.value = null
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("$server/connect-app?state=$state"))
    }

    fun handle(uri: Uri) {
        if (uri.scheme != "aurral" || uri.host != "connect") return
        val server = prefs.getString("server", null)
        val state = prefs.getString("state", null)
        val key = uri.getQueryParameter("key").orEmpty()
        val returned = uri.getQueryParameter("server")?.toHttpUrlOrNull()
        if (server == null || state == null || uri.getQueryParameter("state") != state || key.isBlank()) {
            error.value = "That sign-in link wasn't started from this app. Try again."
            return
        }
        if (returned != null && returned.host != server.toHttpUrlOrNull()?.host) {
            error.value = "The server that answered isn't the one you entered"
            return
        }
        prefs.edit().clear().apply()
        val app = AurralApp.instance
        busy.value = true
        app.scope.launch {
            try {
                val account = Account(server, "", "", newSalt(), passwordAuth = false, apiKey = key)
                SubsonicClient(account, app.http).ping()
                app.accounts.save(account)
                SyncScheduler.applySchedule(app)
                error.value = null
            } catch (e: Exception) {
                error.value = describe(e)
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun SignInScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val app = AurralApp.instance
    val context = LocalContext.current
    val existing = app.accounts.state.value
    var url by remember { mutableStateOf(existing?.serverUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var manual by remember { mutableStateOf(existing != null && existing.username.isNotBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val linkError by BrowserLink.error.collectAsState()
    val linking by BrowserLink.busy.collectAsState()
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
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { BrowserLink.start(context, url) },
                enabled = !linking && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (linking) "Connecting…" else "Sign in with your browser") }
            Text(
                "Opens your server's sign-in page, including any login your proxy asks for, then comes back here.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Glass.Muted,
            )
            linkError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "Hide password sign-in" else "Use username and password instead")
            }
            if (manual) {
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
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("App key") },
                    supportingText = { Text("Your API key from Aurral, Settings > Profile > API access") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                app.accounts.save(signIn(url, username, password, apiKey))
                                SyncScheduler.applySchedule(app)
                                onDone()
                            } catch (e: Exception) {
                                error = describe(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Checking…" else "Sign in") }
                Text(
                    "Behind a sign-in proxy such as Authentik, let /rest/* through without the proxy login. An account " +
                        "created by the proxy needs a password set in Aurral first; browser sign-in doesn't.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.Muted,
                )
            }
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
