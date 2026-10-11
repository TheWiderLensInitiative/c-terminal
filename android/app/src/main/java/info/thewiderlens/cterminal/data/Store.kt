package info.thewiderlens.cterminal.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A computer running the C Terminal companion, reached over Tailscale. */
@Serializable
data class Machine(
    val id: String,
    val name: String,
    val host: String,          // MagicDNS name ("clara-pc") or Tailscale IP
    val port: Int = 7420,
) {
    val base get() = "http://${if (host.contains(':') && !host.startsWith('[')) "[$host]" else host}:$port"
}

data class Settings(
    val away: Boolean = false,            // Claude Code asks this phone instead of the PC screen
    val notifyFinished: Boolean = true,
    val notifyWaiting: Boolean = true,
    val notifyAgents: Boolean = true,
    val fontSize: Float = 13f,
)

private val Context.dataStore by preferencesDataStore("c_terminal")

class Store(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val machinesKey = stringPreferencesKey("machines")
    private val awayKey = booleanPreferencesKey("away")
    private val finishedKey = booleanPreferencesKey("notify_finished")
    private val waitingKey = booleanPreferencesKey("notify_waiting")
    private val agentsKey = booleanPreferencesKey("notify_agents")
    private val fontKey = floatPreferencesKey("font_size")

    val machines: Flow<List<Machine>> = context.dataStore.data.map { p ->
        p[machinesKey]?.let { runCatching { json.decodeFromString<List<Machine>>(it) }.getOrNull() } ?: emptyList()
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(p[awayKey] ?: false, p[finishedKey] ?: true, p[waitingKey] ?: true, p[agentsKey] ?: true, p[fontKey] ?: 13f)
    }

    suspend fun machinesNow() = machines.first()
    suspend fun settingsNow() = settings.first()

    suspend fun saveMachine(m: Machine) = context.dataStore.edit { p ->
        val list = (p[machinesKey]?.let { json.decodeFromString<List<Machine>>(it) } ?: emptyList()).filter { it.id != m.id } + m
        p[machinesKey] = json.encodeToString(list)
    }

    suspend fun removeMachine(id: String) = context.dataStore.edit { p ->
        val list = (p[machinesKey]?.let { json.decodeFromString<List<Machine>>(it) } ?: emptyList()).filter { it.id != id }
        p[machinesKey] = json.encodeToString(list)
    }

    suspend fun setAway(on: Boolean) = context.dataStore.edit { it[awayKey] = on }
    suspend fun setNotify(finished: Boolean? = null, waiting: Boolean? = null, agents: Boolean? = null) =
        context.dataStore.edit { p ->
            finished?.let { p[finishedKey] = it }
            waiting?.let { p[waitingKey] = it }
            agents?.let { p[agentsKey] = it }
        }
    suspend fun setFontSize(size: Float) = context.dataStore.edit { it[fontKey] = size }
}
