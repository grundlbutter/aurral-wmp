package org.aurral.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import org.aurral.android.AurralApp
import org.aurral.android.data.SelectionEntity
import org.aurral.android.playback.PlaybackService
import org.aurral.android.sync.SyncScheduler

class MainActivity : ComponentActivity() {
    private val controller = mutableStateOf<MediaController?>(null)
    private var pending: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) intent?.data?.let(BrowserLink::handle)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AurralTheme {
                CompositionLocalProvider(LocalPlayer provides controller.value) {
                    val account by AurralApp.instance.accounts.state.collectAsStateWithLifecycle()
                    if (account == null) SignInScreen(onDone = {}) else AppShell()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(BrowserLink::handle)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        pending = future
        future.addListener({ controller.value = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller.value = null
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
        super.onStop()
    }
}

fun selectionChange(kind: String, id: String, name: String, selected: Boolean) {
    val app = AurralApp.instance
    app.scope.launch {
        val dao = app.db.library()
        if (selected) dao.upsertSelection(SelectionEntity(kind, id, name)) else dao.deleteSelection(kind, id)
        SyncScheduler.syncNow(app)
    }
}

fun NavHostController.open(route: String, id: String) = navigate("$route/${Uri.encode(id)}")

private val tabs = listOf(
    Triple("phone", "On this phone", Icons.Filled.PhoneAndroid),
    Triple("server", "Aurral", Icons.Filled.Cloud),
    Triple("sync", "Sync", Icons.Filled.Sync),
)

@Composable
private fun AppShell() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Column(Modifier.fillMaxSize().background(Glass.page)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "phone") {
                composable("phone") { PhoneHome(nav) }
                composable("phone/artist/{id}") { PhoneArtist(nav, it.arguments?.getString("id").orEmpty()) }
                composable("phone/album/{id}") { PhoneAlbum(nav, it.arguments?.getString("id").orEmpty()) }
                composable("phone/playlist/{id}") { PhonePlaylist(nav, it.arguments?.getString("id").orEmpty()) }
                composable("server") { ServerHome(nav) }
                composable("server/artist/{id}") { ServerArtist(nav, it.arguments?.getString("id").orEmpty()) }
                composable("server/album/{id}") { ServerAlbum(nav, it.arguments?.getString("id").orEmpty()) }
                composable("server/playlist/{id}") { ServerPlaylist(nav, it.arguments?.getString("id").orEmpty()) }
                composable("server/favorites") { ServerFavorites(nav) }
                composable("sync") { SyncScreen(nav) }
                composable("settings") { SignInScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
                composable("playing") { NowPlayingScreen(onBack = { nav.popBackStack() }) }
            }
        }
        if (route != "playing") {
            MiniPlayer(onOpen = { nav.navigate("playing") })
            NavigationBar(containerColor = Glass.Deep) {
                tabs.forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = route?.substringBefore('/') == tab,
                        onClick = {
                            nav.navigate(tab) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Glass.Panel),
                    )
                }
            }
        }
    }
}

/** The glossy band across the top of each screen. */
@Composable
fun GlassTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Glass.bar).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
            Text(
                title,
                Modifier.weight(1f).padding(horizontal = if (onBack == null) 12.dp else 4.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x5584C8FF)))
    }
}
