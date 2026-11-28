package org.aurral.android

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.aurral.android.api.SubsonicClient
import org.aurral.android.data.AurralDb
import org.aurral.android.playback.Scrobbles
import org.aurral.android.sync.SyncScheduler
import java.util.concurrent.TimeUnit

class AurralApp : Application() {
    lateinit var accounts: AccountStore
        private set
    lateinit var syncOptions: SyncOptionsStore
        private set
    lateinit var db: AurralDb
        private set
    lateinit var scrobbles: Scrobbles
        private set
    val scope = CoroutineScope(SupervisorJob())

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        accounts = AccountStore(this)
        syncOptions = SyncOptionsStore(this)
        db = Room.databaseBuilder(this, AurralDb::class.java, "aurral.db").build()
        scrobbles = Scrobbles(this)
        SyncScheduler.applySchedule(this)
    }

    fun api(): SubsonicClient? = accounts.state.value?.let { SubsonicClient(it, http) }

    companion object {
        lateinit var instance: AurralApp
            private set
    }
}

data class Account(
    val serverUrl: String,
    val username: String,
    val password: String,
    val salt: String,
    val passwordAuth: Boolean,
)

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Account?> = _state.asStateFlow()

    private fun read(): Account? {
        val url = prefs.getString("serverUrl", null) ?: return null
        return Account(
            serverUrl = url,
            username = prefs.getString("username", "").orEmpty(),
            password = prefs.getString("password", "").orEmpty(),
            salt = prefs.getString("salt", "").orEmpty(),
            passwordAuth = prefs.getBoolean("passwordAuth", false),
        )
    }

    fun save(account: Account) {
        prefs.edit()
            .putString("serverUrl", account.serverUrl)
            .putString("username", account.username)
            .putString("password", account.password)
            .putString("salt", account.salt)
            .putBoolean("passwordAuth", account.passwordAuth)
            .apply()
        _state.value = account
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _state.value = null
    }
}

data class SyncOptions(
    val wifiOnly: Boolean = true,
    val daily: Boolean = true,
    val removeUnselected: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastResult: String? = null,
)

class SyncOptionsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncOptions> = _state.asStateFlow()

    private fun read() = SyncOptions(
        wifiOnly = prefs.getBoolean("wifiOnly", true),
        daily = prefs.getBoolean("daily", true),
        removeUnselected = prefs.getBoolean("removeUnselected", true),
        lastSyncAt = prefs.getLong("lastSyncAt", 0),
        lastResult = prefs.getString("lastResult", null),
    )

    fun update(change: (SyncOptions) -> SyncOptions) {
        val next = change(_state.value)
        prefs.edit()
            .putBoolean("wifiOnly", next.wifiOnly)
            .putBoolean("daily", next.daily)
            .putBoolean("removeUnselected", next.removeUnselected)
            .putLong("lastSyncAt", next.lastSyncAt)
            .putString("lastResult", next.lastResult)
            .apply()
        _state.value = next
    }
}
