package info.thewiderlens.cterminal.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import info.thewiderlens.cterminal.CTerminalApp
import info.thewiderlens.cterminal.data.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Buttons on notifications: Allow / Deny / Always allow, and Reply (types into the session). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as CTerminalApp
        CoroutineScope(Dispatchers.IO).launch {
            val notif = intent.getIntExtra("notif", 0)
            val machine = app.store.machinesNow().firstOrNull { it.id == intent.getStringExtra("machine") }
            val result = runCatching {
                if (machine == null) error("That computer was removed")
                when (intent.action) {
                    ANSWER -> {
                        val approval = intent.getStringExtra("approval")!!
                        Api.answer(machine, approval, intent.getStringExtra("decision")!!)
                        Inbox.answered(approval)
                    }
                    REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.REPLY_KEY)?.toString()
                        if (text != null) Api.input(machine, intent.getStringExtra("session")!!, text)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                NotificationManagerCompat.from(context).cancel(notif)
                result.exceptionOrNull()?.let { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                pending.finish()
            }
        }
    }

    companion object {
        const val ANSWER = "info.thewiderlens.cterminal.ANSWER"
        const val REPLY = "info.thewiderlens.cterminal.REPLY"
    }
}
