package info.thewiderlens.cterminal.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import info.thewiderlens.cterminal.CTerminalApp
import info.thewiderlens.cterminal.R
import info.thewiderlens.cterminal.data.Api
import info.thewiderlens.cterminal.data.Machine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Keeps one small live connection to each computer so the phone hears about finished commands, prompts waiting for
 * an answer, and Claude Code approvals. Runs as a foreground service (a quiet, silent notification) so Android
 * doesn't cut the connection.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watches = mutableMapOf<String, Watch>()
    @Volatile private var away = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, quietNotification(0),
            if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        val store = (application as CTerminalApp).store
        scope.launch {
            combine(store.machines, store.settings) { m, s -> m to s.away }.distinctUntilChanged().collect { (machines, awayNow) ->
                away = awayNow
                val ids = machines.map { it.id }.toSet()
                watches.keys.filter { it !in ids }.forEach { id -> watches.remove(id)?.stop(); Inbox.forget(id) }
                for (m in machines) {
                    val w = watches[m.id]
                    if (w == null || w.machine != m) {
                        w?.stop()
                        watches[m.id] = Watch(m).also { it.start() }
                    } else {
                        w.sendAway()
                    }
                }
                if (machines.isEmpty()) stopSelf()
                updateQuiet()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        watches.values.forEach { it.stop() }
        scope.cancel()
        super.onDestroy()
    }

    private fun updateQuiet() {
        val n = watches.values.count { it.connected }
        getSystemService(android.app.NotificationManager::class.java).notify(Notifier.SERVICE_ID, quietNotification(n))
    }

    private fun quietNotification(connected: Int) = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (away) "Away mode: approvals come here" else "C Terminal")
        .setContentText(when (connected) { 0 -> "Connecting to your computers…"; 1 -> "Connected to 1 computer"; else -> "Connected to $connected computers" })
        .setContentIntent(Notifier.openApp(this, null, null))
        .setOngoing(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private inner class Watch(val machine: Machine) {
        private var job: Job? = null
        private var ws: WebSocket? = null
        private var lastSeq = -1L
        @Volatile var connected = false

        fun start() {
            job = scope.launch {
                var backoff = 2_000L
                while (true) {
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val since = if (lastSeq >= 0) "&since=$lastSeq" else ""
                    ws = Api.socket(machine, "/ws/events?approvals=${if (away) 1 else 0}$since", object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            connected = true; backoff = 2_000L
                            Inbox.setOnline(machine.id, true); updateQuiet()
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val e = runCatching { Api.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                            e["seq"]?.jsonPrimitive?.longOrNull?.let { if (it > lastSeq) lastSeq = it }
                            handle(e)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                    })
                    closed.await()
                    if (connected) { connected = false; Inbox.setOnline(machine.id, false); updateQuiet() }
                    else Inbox.setOnline(machine.id, false)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }

        fun sendAway() { ws?.send("""{"type":"approvals","on":$away}""") }

        fun stop() {
            job?.cancel(); ws?.cancel(); connected = false
        }

        private fun handle(e: JsonObject) {
            val item = InboxItem(machine.id, machine.name, e)
            Inbox.add(item)
            scope.launch { Notifier.show(this@WatchService, item) }
        }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }
    }
}
