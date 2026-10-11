package info.thewiderlens.cterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.thewiderlens.cterminal.CTerminalApp
import info.thewiderlens.cterminal.R
import info.thewiderlens.cterminal.data.Api
import info.thewiderlens.cterminal.data.ApiError
import info.thewiderlens.cterminal.data.Machine
import info.thewiderlens.cterminal.data.SessionInfo
import info.thewiderlens.cterminal.watch.Inbox
import info.thewiderlens.cterminal.watch.InboxItem
import info.thewiderlens.cterminal.watch.Notifier
import info.thewiderlens.cterminal.watch.WatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Sessions(val machineId: String) : Screen
    data class Terminal(val machineId: String, val sessionId: String, val name: String) : Screen
}

@Composable
fun AppRoot(screen: Screen, go: (Screen) -> Unit) {
    val store = (LocalContext.current.applicationContext as CTerminalApp).store
    val machines by store.machines.collectAsState(initial = null)
    val list = machines ?: return
    when (screen) {
        Screen.Home -> HomeScreen(list, go)
        is Screen.Sessions -> list.firstOrNull { it.id == screen.machineId }?.let { SessionsScreen(it, go) }
            ?: LaunchedEffect(Unit) { go(Screen.Home) }
        is Screen.Terminal -> {
            val m = list.firstOrNull { it.id == screen.machineId }
            if (m == null) LaunchedEffect(Unit) { go(Screen.Home) } else {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                androidx.compose.runtime.key(screen.sessionId) {
                    TerminalScreen(m, screen.sessionId, screen.name.ifBlank { "Session" },
                        onBack = { go(Screen.Sessions(m.id)) },
                        onKill = { scope.launch { runCatching { Api.kill(m, screen.sessionId) }.onFailure { toast(context, it.message) }; go(Screen.Sessions(m.id)) } })
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------- home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(machines: List<Machine>, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as CTerminalApp).store
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsState(initial = null)
    val pending by Inbox.pending.collectAsState()
    val recent by Inbox.recent.collectAsState()
    val online by Inbox.online.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Machine?>(null) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
                        Text("C ", color = Mint, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("Terminal", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                },
                actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, containerColor = Mint, contentColor = Ink,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add computer") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (machines.isEmpty()) {
                item { Onboarding() }
            } else {
                item {
                    val away = settings?.away == true
                    Card(colors = CardDefaults.cardColors(containerColor = if (away) Mint.copy(alpha = 0.12f) else Panel),
                        modifier = Modifier.border(1.dp, if (away) Mint else Color.Transparent, RoundedCornerShape(12.dp))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Away mode", fontWeight = FontWeight.SemiBold)
                                Text(if (away) "Claude Code asks this phone before it runs anything."
                                     else "Turn on when you leave: Claude Code approvals come to this phone instead of the PC screen.",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = away, onCheckedChange = { scope.launch { store.setAway(it) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Ink))
                        }
                    }
                }
            }
            if (pending.isNotEmpty()) {
                item { Section("Waiting for your OK") }
                items(pending, key = { "p" + it.machineId + it.approval }) { ApprovalCard(it, machines, go) }
            }
            if (machines.isNotEmpty()) item { Section("Computers") }
            items(machines, key = { it.id }) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel),
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { go(Screen.Sessions(m.id)) }, onLongClick = { removing = m })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val on = online[m.id]
                        Box(Modifier.size(10.dp).background(when (on) { true -> Mint; false -> Danger; null -> Color.Gray }, CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(when (on) { true -> "Connected"; false -> "Can't reach it right now"; null -> "Connecting…" } + " · ${m.host}",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", fontSize = 26.sp, color = Mint)
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { Section("Recent", action = "Clear all") { Inbox.clearRecent() } }
                items(recent, key = { "r" + it.machineId + it.str("seq") + it.type }) { item ->
                    SwipeAway(onSwiped = { Inbox.dismiss(item) }, modifier = Modifier.animateItem()) { RecentRow(item, go) }
                }
            }
        }
    }

    if (adding) AddMachineDialog(onDone = { adding = false })
    if (showSettings) settings?.let { s ->
        AlertDialog(onDismissRequest = { showSettings = false }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } },
            title = { Text("Notifications") },
            text = {
                Column {
                    Toggle("Long commands finished", s.notifyFinished) { scope.launch { store.setNotify(finished = it) } }
                    Toggle("A program is waiting for an answer", s.notifyWaiting) { scope.launch { store.setNotify(waiting = it) } }
                    Toggle("Claude Code / Codex finished or need you", s.notifyAgents) { scope.launch { store.setNotify(agents = it) } }
                    Spacer(Modifier.height(8.dp))
                    Text("Long-press a computer to remove it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
    }
    removing?.let { m ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("Its sessions keep running on the PC. You can add it again any time.") },
            confirmButton = { TextButton(onClick = { scope.launch { store.removeMachine(m.id) }; removing = null }) { Text("Remove", color = Danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Onboarding() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your computer's terminal, in your pocket", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("1.  Install Tailscale on this phone and on your computer, signed in to the same account.", fontSize = 14.sp)
            Text("2.  On the computer, run:", fontSize = 14.sp)
            Text("curl -fsSL https://raw.githubusercontent.com/TheWiderLensInitiative/c-terminal/main/install.sh | bash",
                fontFamily = Mono, fontSize = 12.sp, color = Mint,
                modifier = Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(8.dp)).padding(10.dp))
            Text("3.  Tap “Add computer” and type the name it shows (like clara-pc).", fontSize = 14.sp)
            Text("No passwords or keys: your Tailscale account is the key.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 1.5.sp, color = Mint, modifier = Modifier.weight(1f))
        if (action != null) Text(action, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Swipe a row sideways to remove it. A red strip with a trash can shows underneath while swiping. */
@Composable
fun SwipeAway(onSwiped: () -> Unit, modifier: Modifier = Modifier, confirm: Boolean = false, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().background(Danger.copy(alpha = 0.85f), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
                contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart) {
                Icon(Icons.Default.Delete, "Remove", tint = Ink)
            }
        },
        onDismiss = {
            onSwiped()
            if (confirm) scope.launch { state.reset() }   // the caller asks first; spring back meanwhile
        },
        content = { content() },
    )
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = on, onCheckedChange = set, colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Ink))
    }
}

@Composable
private fun ApprovalCard(item: InboxItem, machines: List<Machine>, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val m = machines.firstOrNull { it.id == item.machineId } ?: return
    fun answer(decision: String) {
        busy = true
        scope.launch {
            runCatching { Api.answer(m, item.approval!!, decision); Inbox.answered(item.approval!!) }.onFailure { toast(context, it.message) }
            busy = false
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = PanelHigh), modifier = Modifier.border(1.dp, Mint.copy(alpha = 0.6f), RoundedCornerShape(12.dp))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.str("agent") ?: "Agent"} wants to use ${item.str("tool") ?: "a tool"}", fontWeight = FontWeight.SemiBold)
            Text(item.str("summary") ?: "", fontFamily = Mono, fontSize = 13.sp, color = Mint,
                modifier = Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(8.dp)).padding(10.dp))
            Text(listOfNotNull(m.name, item.str("session_name"), item.str("cwd")).joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Mint) else {
                    Button(onClick = { answer("allow") }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink)) { Text("Allow") }
                    OutlinedButton(onClick = { answer("deny") }) { Text("Deny", color = Danger) }
                    if (item.str("can_always") == "true") TextButton(onClick = { answer("always") }) { Text("Always") }
                }
                Spacer(Modifier.weight(1f))
                item.session?.let { s -> TextButton(onClick = { go(Screen.Terminal(m.id, s, item.str("session_name") ?: "")) }) { Text("Open") } }
            }
        }
    }
}

@Composable
private fun RecentRow(item: InboxItem, go: (Screen) -> Unit) {
    val (icon, title, detail) = when (item.type) {
        "finished" -> {
            val ok = item.str("exit_code") == "0"
            Triple(if (ok) "✅" else "❌", item.str("command") ?: "",
                (if (ok) "Finished" else "Failed (exit ${item.str("exit_code")})") + " in " + Notifier.duration(item.str("seconds")?.toDoubleOrNull() ?: 0.0))
        }
        "waiting" -> Triple("⌨️", item.str("reason") ?: "Waiting", item.str("prompt") ?: "")
        "agent_waiting" -> Triple("💬", item.str("title") ?: "Needs you", item.str("message") ?: "")
        "agent_done" -> Triple("✨", "${item.str("agent")} is done", item.str("message") ?: "")
        "approval" -> Triple("🔐", "${item.str("agent")}: ${item.str("tool")}", item.str("summary") ?: "")
        "exited" -> Triple("⏹", "Session ended", item.str("session_name") ?: "")
        else -> Triple("•", item.type, "")
    }
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
        .combinedClickable(enabled = item.session != null && item.type != "exited",
            onClick = { go(Screen.Terminal(item.machineId, item.session!!, item.str("session_name") ?: "")) })
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val sub = listOfNotNull(detail.ifBlank { null }, item.machineName).joinToString(" · ")
            Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddMachineDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as CTerminalApp).store
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7420") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add a computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Its Tailscale name (shown by the installer, or in the Tailscale app), or its 100.x.y.z address.", fontSize = 13.sp)
                OutlinedTextField(host, { host = it.trim() }, label = { Text("Name or address") }, singleLine = true, placeholder = { Text("clara-pc") })
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true)
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = host.isNotBlank() && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    val probe = Machine(UUID.randomUUID().toString(), host, host, port.toIntOrNull() ?: 7420)
                    try {
                        val info = Api.info(probe)
                        store.saveMachine(probe.copy(name = info.name.ifBlank { host }))
                        WatchService.start(context)
                        onDone()
                    } catch (e: ApiError) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "That didn't answer like a C Terminal companion."
                    }
                    busy = false
                }
            }) { Text(if (busy) "Checking…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------------------------- sessions
private data class Launch(val label: String, val run: String?, val name: String?)

private val LAUNCHES = listOf(
    Launch("Shell", null, null),
    Launch("Claude Code", "claude", "Claude Code"),
    Launch("Codex", "codex --no-alt-screen", "Codex"),   // inline: its conversation stays scrollable
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(machine: Machine, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf<SessionInfo?>(null) }
    BackHandler { go(Screen.Home) }

    LaunchedEffect(machine.id) {
        while (true) {
            try { sessions = Api.sessions(machine); error = null } catch (e: ApiError) { error = e.message }
            delay(3000)
        }
    }

    fun start(run: String?, name: String?) = scope.launch {
        try {
            val s = Api.create(machine, run = run, name = name)
            go(Screen.Terminal(machine.id, s.id, s.name))
        } catch (e: ApiError) { toast(context, e.message) }
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Column { Text(machine.name, fontWeight = FontWeight.Bold); Text(machine.host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { go(Screen.Home) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Section("Start") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in LAUNCHES) TextKey(l.label) { start(l.run, l.name) }
                    TextKey("Command…") { custom = true }
                }
            }
            error?.let { item { Text(it, color = Danger, fontSize = 14.sp) } }
            val list = sessions
            val ended = list.orEmpty().filter { !it.alive }
            item {
                if (ended.isEmpty()) Section("Sessions")
                else Section("Sessions", action = "Clear ended (${ended.size})") {
                    scope.launch {
                        ended.forEach { runCatching { Api.kill(machine, it.id) } }
                        sessions = sessions?.filter { it.alive }
                    }
                }
            }
            when {
                list == null && error == null -> item { CircularProgressIndicator(color = Mint) }
                list.isNullOrEmpty() -> item {
                    Text("Nothing running yet. Start a shell, Claude Code or Codex above: it keeps running on ${machine.name} even when you close the app.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    items(list, key = { it.id }) { s ->
                        SwipeAway(onSwiped = { ending = s }, confirm = true, modifier = Modifier.animateItem()) {
                            SessionCard(s, open = { go(Screen.Terminal(machine.id, s.id, s.name)) }, end = { ending = s })
                        }
                    }
                }
            }
        }
    }

    ending?.let { s ->
        fun end() {
            ending = null
            scope.launch {
                runCatching { Api.kill(machine, s.id) }.onFailure { toast(context, it.message) }
                sessions = sessions?.filterNot { it.id == s.id }
            }
        }
        if (!s.alive) end() else AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("End “${s.name}”?") },
            text = { Text(if (s.command != null) "“${s.command}” is still running on ${machine.name}. Ending the session stops it." else "This closes the shell on ${machine.name}. Its scrollback is gone after that.") },
            confirmButton = { TextButton(onClick = { end() }) { Text("End session", color = Danger) } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Keep") } })
    }

    if (custom) {
        var cmd by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { custom = false }, title = { Text("Run a command") },
            text = { OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") }, placeholder = { Text("htop") }, singleLine = true) },
            confirmButton = { TextButton(enabled = cmd.isNotBlank(), onClick = { custom = false; start(cmd, null) }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SessionCard(s: SessionInfo, open: () -> Unit, end: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = open)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (!s.alive) Color.Gray else if (s.command != null) Mint else MintDeep.copy(alpha = 0.5f), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(s.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(when {
                    !s.alive -> "ended"
                    s.command != null -> "running · " + Notifier.duration(s.running_for)
                    else -> "idle"
                }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = end, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "End session", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            s.command?.let { Text("$ $it", fontFamily = Mono, fontSize = 12.sp, color = Mint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val preview = s.preview.trimEnd().lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
            if (preview.isNotBlank()) Text(preview, fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 4,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(8.dp)).padding(8.dp))
            if (s.cwd.isNotBlank()) Text(s.cwd, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
