package info.thewiderlens.cterminal.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import info.thewiderlens.cterminal.CTerminalApp
import info.thewiderlens.cterminal.data.Api
import info.thewiderlens.cterminal.data.Machine
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/** Sticky modifier keys from the extra-keys row; they apply to the next key typed anywhere, then release. */
class Mods {
    var shift by mutableStateOf(false)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun apply(data: String): String {
        if (!shift && !ctrl && !alt) return data
        val code = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)   // xterm modifier number
        val csi = Regex("^\u001b\\[([0-9]*)([A-DHF~])$").find(data)
        val out = when {
            // arrows, Home/End, PgUp/PgDn: ESC[1;<mod>A  /  ESC[5;<mod>~
            csi != null -> {
                val (num, fin) = csi.destructured
                if (fin == "~") "\u001b[$num;$code~" else "\u001b[1;$code$fin"
            }
            data == "\t" && shift -> "\u001b[Z"                       // Shift+Tab (Claude Code: switch mode)
            data == "\r" && shift -> "\\\r"                           // Shift+Enter: new line without sending
            data.length == 1 -> {
                var c = data[0]
                if (shift) c = c.uppercaseChar()
                var o = c.toString()
                if (ctrl) o = when (c) {
                    in 'a'..'z' -> (c - 'a' + 1).toChar().toString()
                    in '@'..'_' -> (c - '@').toChar().toString()
                    ' ' -> "\u0000"
                    '?' -> "\u007f"
                    else -> o
                }
                if (alt) "\u001b" + o else o
            }
            else -> data
        }
        shift = false; ctrl = false; alt = false
        return out
    }
}

/** One live connection between the page's terminal and a session on the PC. Reconnects by itself. */
private class TermLink(
    private val machine: Machine,
    private val sessionId: String,
    private val web: WebView,
    private val mods: Mods,
    private val currentFont: () -> Float,
    private val onState: (String?) -> Unit,
    private val onTitle: (String) -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var flushQueued = false
    private var ws: WebSocket? = null
    private var cols = 80
    private var rows = 24
    private var closed = false
    private var ended = false
    private var backoff = 1000L

    @JavascriptInterface fun ready(c: Int, r: Int) { main.post { cols = c; rows = r; connect() } }
    @JavascriptInterface fun input(data: String) { ws?.send(mods.apply(data).toByteArray().toByteString()) }
    @JavascriptInterface fun resize(c: Int, r: Int) { cols = c; rows = r; ws?.send("""{"type":"resize","cols":$c,"rows":$r}""") }
    @JavascriptInterface fun title(t: String) { main.post { onTitle(t) } }
    @JavascriptInterface fun fontSize(): String = currentFont().toString()

    fun send(text: String) { ws?.send(text.toByteArray().toByteString()) }
    fun redraw() { ws?.send("""{"type":"redraw"}""") }

    /** Type a line, then press Enter as a separate keypress. Sent in one burst, apps like Claude Code take
     *  text+Enter for a paste and add a new line instead of submitting. */
    fun sendLine(text: String) {
        val enter = mods.apply("\r")          // with Shift armed: a new line instead of sending
        if (text.isEmpty()) { send(enter); return }
        send(text)
        main.postDelayed({ send(enter) }, 150)
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun connect() {
        if (closed || ended) return
        onState(if (ws == null) "Connecting…" else "Reconnecting…")
        ws = Api.socket(machine, "/ws/term/$sessionId?cols=$cols&rows=$rows", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { main.post { backoff = 1000L; onState(null) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                main.post { pending.write(b); if (!flushQueued) { flushQueued = true; main.postDelayed({ flush() }, 12) } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    flush()
                    when (runCatching { org.json.JSONObject(text).optString("type") }.getOrNull()) {
                        "hello" -> js("ct.reset()")   // the full scrollback follows, so start clean
                        "exit" -> { ended = true; js("ct.note('[session ended]')"); onState("Session ended") }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    if (closed || ended) return@post
                    if (response?.code == 404) { ended = true; onState("This session is gone (the PC companion restarted?)"); return@post }
                    onState("Reconnecting…")
                    main.postDelayed({ connect() }, backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000L)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code == 1001) main.post { backoff = 1000L }   // companion restarting: retry quickly
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onFailure(webSocket, Exception(reason), null)
        })
    }

    private fun flush() {
        flushQueued = false
        if (pending.size() == 0) return
        val b64 = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
        pending.reset()
        js("ct.write('$b64')")
    }

    fun close() { closed = true; ws?.close(1000, null) }
}

private val KEYS = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "Shift" to "", "Ctrl" to "", "Alt" to "", "Paste" to "", "Copy" to "", "←" to "\u001b[D", "↑" to "\u001b[A",
    "↓" to "\u001b[B", "→" to "\u001b[C", "^C" to "\u0003", "|" to "|", "~" to "~", "/" to "/", "-" to "-",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "^D" to "\u0004",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(machine: Machine, sessionId: String, initialName: String, onBack: () -> Unit, onKill: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as CTerminalApp).store
    val scope = rememberCoroutineScope()
    val mods = remember { Mods() }
    var state by remember { mutableStateOf<String?>("Connecting…") }
    var title by remember { mutableStateOf(initialName) }
    var font by remember { mutableStateOf(13f) }
    var line by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<TermLink?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var selectText by remember { mutableStateOf<String?>(null) }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (clip.isNullOrEmpty()) toast(context, "The clipboard is empty")
        else web?.evaluateJavascript("ct.paste(${org.json.JSONObject.quote(clip)})", null)
    }
    fun openSelect() {
        if (selectText != null) return
        web?.evaluateJavascript("ct.text(3000)") { raw ->
            selectText = runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrDefault("")
        }
    }
    /** Copy key: copy what's selected; with nothing selected, open the whole text to pick from. */
    fun copySelected() {
        web?.evaluateJavascript("ct.selected()") { raw ->
            val text = runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrDefault("")
            if (text.isNotEmpty()) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            } else openSelect()
        }
    }

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { link?.close(); web?.destroy() } }

    androidx.compose.material3.Surface(color = Ink, contentColor = Silver) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(state ?: machine.name, fontSize = 12.sp, color = if (state == null) MaterialTheme.colorScheme.onSurfaceVariant else Mint, maxLines = 1)
            }
            TextKey("A−") { font = (font - 1).coerceAtLeast(8f); web?.evaluateJavascript("ct.font($font)", null); scope.launch { store.setFontSize(font) } }
            TextKey("A+") { font = (font + 1).coerceAtMost(28f); web?.evaluateJavascript("ct.font($font)", null); scope.launch { store.setFontSize(font) } }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; paste() })
                    DropdownMenuItem(text = { Text("Select & copy text") }, onClick = { menu = false; openSelect() })
                    DropdownMenuItem(text = { Text("Redraw") }, onClick = { menu = false; link?.redraw() })
                    DropdownMenuItem(text = { Text("End session", color = Danger) }, onClick = { menu = false; onKill() })
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.parseColor("#05080a"))
                    // The terminal's hidden input box isn't a form: no "Autofill" bubbles over it.
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    webViewClient = object : WebViewClient() {
                        // The page is ours and offline; never navigate anywhere else.
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = true
                    }
                    font = kotlinx.coroutines.runBlocking { store.settingsNow().fontSize }
                    val l = TermLink(machine, sessionId, this, mods, { font }, { state = it }, { if (it.isNotBlank()) title = it }, { openSelect() })
                    addJavascriptInterface(l, "Android")
                    link = l
                    web = this
                    loadUrl("file:///android_asset/term/index.html")
                }
            },
        )

        Row(Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, seq) in KEYS) {
                val active = (label == "Ctrl" && mods.ctrl) || (label == "Alt" && mods.alt) || (label == "Shift" && mods.shift)
                TextKey(label, active) {
                    when (label) {
                        "Shift" -> mods.shift = !mods.shift
                        "Ctrl" -> mods.ctrl = !mods.ctrl
                        "Alt" -> mods.alt = !mods.alt
                        "Paste" -> paste()
                        "Copy" -> copySelected()
                        else -> link?.send(mods.apply(seq))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(start = 8.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line, onValueChange = { line = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command or message…", fontSize = 14.sp) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { link?.sendLine(line); line = "" }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(12.dp),
            )
            IconButton(onClick = { link?.sendLine(line); line = "" }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Mint)
            }
        }
    }
    }

    selectText?.let { text -> SelectTextView(text, onClose = { selectText = null }) }
}

/** The terminal's text as ordinary Android text: long-press to select with handles, then Copy; or Copy all. */
@Composable
private fun SelectTextView(text: String, onClose: () -> Unit) = text.run {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(color = Ink, contentColor = Silver, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("Select text", fontWeight = FontWeight.SemiBold)
                        Text("Long-press to select, then Copy", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
                        Toast.makeText(context, "Copied everything", Toast.LENGTH_SHORT).show()
                        onClose()
                    }) { Text("Copy all", color = Mint) }
                }
                // A plain Android TextView: long-press selects a word and the handles extend it. (Compose's selection
                // and Android's "smart selection" grab whole blocks of terminal text, so both are off here.)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    factory = { ctx ->
                        val tv = android.widget.TextView(ctx).apply {
                            textSize = 13f
                            setTextColor(android.graphics.Color.parseColor("#D7DEE0"))
                            setHighlightColor(android.graphics.Color.parseColor("#661ED6A6"))
                            setPadding(28, 20, 28, 40)
                            setTextIsSelectable(true)
                            setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL))
                            if (android.os.Build.VERSION.SDK_INT >= 26) setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
                            setText(this@run.ifEmpty { "(nothing on the screen yet)" })
                        }
                        android.widget.ScrollView(ctx).apply {
                            isFillViewport = true
                            clipToPadding = true
                            clipChildren = true
                            addView(tv)
                            post { fullScroll(android.view.View.FOCUS_DOWN) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun TextKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, if (active) Mint else Color(0xFF2E4246), RoundedCornerShape(8.dp))
            .background(if (active) Mint.copy(alpha = 0.18f) else PanelHigh, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
    ) { Text(label, fontFamily = Mono, fontSize = 13.sp, color = if (active) Mint else Silver) }
}

fun toast(context: Context, msg: String?) = Toast.makeText(context, msg ?: "Something went wrong", Toast.LENGTH_LONG).show()
