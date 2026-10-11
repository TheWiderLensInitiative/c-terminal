package info.thewiderlens.cterminal

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import info.thewiderlens.cterminal.data.Store
import info.thewiderlens.cterminal.watch.Notifier
import info.thewiderlens.cterminal.watch.WatchService
import kotlinx.coroutines.runBlocking

class CTerminalApp : Application() {
    val store by lazy { Store(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
    }
}

/** After a reboot or an app update, reconnect to the computers (if there are any). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CTerminalApp
        val any = runBlocking { app.store.machinesNow().isNotEmpty() }
        if (any) runCatching { WatchService.start(context) }
    }
}
