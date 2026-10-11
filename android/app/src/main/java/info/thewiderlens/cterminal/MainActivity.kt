package info.thewiderlens.cterminal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import info.thewiderlens.cterminal.ui.AppRoot
import info.thewiderlens.cterminal.ui.CTerminalTheme
import info.thewiderlens.cterminal.ui.Screen
import info.thewiderlens.cterminal.watch.WatchService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        lifecycleScope.launch {
            if ((application as CTerminalApp).store.machinesNow().isNotEmpty()) WatchService.start(this@MainActivity)
        }
        setContent { CTerminalTheme { AppRoot(screen) { screen = it } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A notification was tapped: open that session (or the machine's sessions). */
    private fun handle(intent: Intent?) {
        val machine = intent?.getStringExtra("machine") ?: return
        val session = intent.getStringExtra("session")
        screen = if (session != null) Screen.Terminal(machine, session, "") else Screen.Sessions(machine)
    }
}
