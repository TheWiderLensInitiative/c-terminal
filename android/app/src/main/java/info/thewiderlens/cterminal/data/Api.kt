package info.thewiderlens.cterminal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

@Serializable
data class MachineInfo(
    val name: String = "",
    val version: String = "",
    val login: String = "",
    val os: String = "",
    val sessions: Int = 0,
    val pending_approvals: Int = 0,
)

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val cwd: String = "",
    val command: String? = null,
    val running_for: Double = 0.0,
    val alive: Boolean = true,
    val exit_code: Int? = null,
    val attached: Int = 0,
    val preview: String = "",
)

class ApiError(message: String) : Exception(message)

/** Talks to one companion. Every request carries X-CTerminal: 1 (the companion refuses anything without it). */
object Api {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-CTerminal", "1").build()) }
        .build()

    private val jsonType = "application/json".toMediaType()

    private suspend fun call(m: Machine, method: String, path: String, body: JsonObject? = null): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(m.base + path)
                .method(method, body?.toString()?.toRequestBody(jsonType) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(jsonType))
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    val text = r.body.string()
                    when {
                        r.isSuccessful -> text
                        r.code == 403 -> throw ApiError("This phone isn't allowed on ${m.name}. Is it on the same Tailscale account?")
                        r.code == 404 -> throw ApiError("Not found: the companion may have restarted.")
                        r.code == 410 -> throw ApiError("Already answered or expired.")
                        else -> throw ApiError(text.ifBlank { "Error ${r.code}" })
                    }
                }
            } catch (e: java.io.IOException) {
                throw ApiError("Can't reach ${m.host}. Is Tailscale on, and is the companion running on the PC?")
            }
        }

    suspend fun info(m: Machine): MachineInfo = json.decodeFromString(call(m, "GET", "/api/info"))
    suspend fun sessions(m: Machine): List<SessionInfo> = json.decodeFromString(call(m, "GET", "/api/sessions"))
    suspend fun create(m: Machine, run: String? = null, name: String? = null, cols: Int = 80, rows: Int = 24): SessionInfo =
        json.decodeFromString(call(m, "POST", "/api/sessions", buildJsonObject {
            run?.let { put("run", it) }; name?.let { put("name", it) }; put("cols", cols); put("rows", rows)
        }))
    suspend fun rename(m: Machine, id: String, name: String) { call(m, "PATCH", "/api/sessions/$id", buildJsonObject { put("name", name) }) }
    suspend fun kill(m: Machine, id: String) { call(m, "DELETE", "/api/sessions/$id") }
    suspend fun input(m: Machine, id: String, text: String) { call(m, "POST", "/api/sessions/$id/input", buildJsonObject { put("text", text) }) }
    suspend fun answer(m: Machine, approval: String, decision: String) {
        call(m, "POST", "/api/approvals/$approval", buildJsonObject { put("decision", decision) })
    }
    suspend fun events(m: Machine, since: Long): JsonElement = json.parseToJsonElement(call(m, "GET", "/api/events?since=$since"))

    fun socket(m: Machine, path: String, listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(m.base.replace("http://", "ws://") + path).build(), listener)
}
