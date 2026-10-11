package info.thewiderlens.cterminal.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import info.thewiderlens.cterminal.CTerminalApp
import info.thewiderlens.cterminal.MainActivity
import info.thewiderlens.cterminal.R
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_APPROVALS = "approvals"
    const val CH_WAITING = "waiting"
    const val CH_FINISHED = "finished"
    const val SERVICE_ID = 1
    const val REPLY_KEY = "reply"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Claude Code asking to run something (in away mode)"
            },
            NotificationChannel(CH_WAITING, "Waiting for you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A program or agent is waiting for your answer"
            },
            NotificationChannel(CH_FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Long commands and agent turns that finished"
            },
            NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The quiet notification that keeps C Terminal connected"
                setShowBadge(false)
            },
        ))
    }

    fun openApp(context: Context, machineId: String?, session: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("machine", machineId).putExtra("session", session)
        return PendingIntent.getActivity(context, "$machineId/$session".hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun approvalId(item: InboxItem) = "a/${item.machineId}/${item.approval}".hashCode()
    private fun sessionId(item: InboxItem) = "s/${item.machineId}/${item.session}".hashCode()

    suspend fun show(context: Context, item: InboxItem) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val settings = (context.applicationContext as CTerminalApp).store.settingsNow()
        val time = item.event["time"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val fresh = System.currentTimeMillis() / 1000.0 - time < 30 * 60   // don't replay old news after a long gap
        val where = listOfNotNull(item.machineName, item.str("session_name")).joinToString(" · ")

        when (item.type) {
            "approval" -> {
                val agent = item.str("agent") ?: "An agent"
                val tool = item.str("tool") ?: ""
                val what = item.str("summary") ?: tool
                val b = base(context, CH_APPROVALS, item)
                    .setContentTitle("$agent wants to use $tool")
                    .setContentText(what)
                    .setStyle(NotificationCompat.BigTextStyle().bigText("$what\n\n${item.str("cwd") ?: ""}".trim()).setSummaryText(where))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setOngoing(true)
                    .addAction(answer(context, item, "allow", "Allow", true))
                    .addAction(answer(context, item, "deny", "Deny", false))
                if (item.str("can_always") == "true") b.addAction(answer(context, item, "always", "Always allow", true))
                post(context, approvalId(item), b)
            }
            "approval_closed" -> nm.cancel(approvalId(item))
            "waiting", "agent_waiting" -> if (fresh && (if (item.type == "waiting") settings.notifyWaiting else settings.notifyAgents)) {
                val title = if (item.type == "waiting") (item.str("reason") ?: "Waiting for you") else (item.str("title") ?: "${item.str("agent")} needs you")
                val text = item.str("prompt") ?: item.str("message") ?: ""
                val b = base(context, CH_WAITING, item).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text).setSummaryText(where))
                if (item.session != null) b.addAction(reply(context, item))
                post(context, sessionId(item), b)
            }
            "finished" -> if (fresh && settings.notifyFinished) {
                val code = item.str("exit_code")?.toIntOrNull() ?: 0
                val secs = item.event["seconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val cmd = item.str("command") ?: ""
                val b = base(context, CH_FINISHED, item)
                    .setContentTitle("${if (code == 0) "✅" else "❌"} ${cmd.take(60)}")
                    .setContentText(if (code == 0) "Finished in ${duration(secs)}" else "Failed (exit $code) after ${duration(secs)}")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(item.str("tail")?.trim()?.takeLast(400) ?: "").setSummaryText(where))
                post(context, "f/${item.machineId}/${item.str("seq")}".hashCode(), b)
            }
            "agent_done" -> if (fresh && settings.notifyAgents) {
                val msg = item.str("message").orEmpty().ifBlank { "Finished its turn" }
                val b = base(context, CH_FINISHED, item)
                    .setContentTitle("${item.str("agent") ?: "Agent"} is done")
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg).setSummaryText(where))
                if (item.session != null) b.addAction(reply(context, item))
                post(context, sessionId(item), b)
            }
        }
    }

    private fun base(context: Context, channel: String, item: InboxItem) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF5CFDED.toInt())
        .setContentIntent(openApp(context, item.machineId, item.session))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun post(context: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(context).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    private fun answer(context: Context, item: InboxItem, decision: String, label: String, unlock: Boolean): NotificationCompat.Action {
        val i = Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ANSWER)
            .putExtra("machine", item.machineId).putExtra("approval", item.approval)
            .putExtra("decision", decision).putExtra("notif", approvalId(item))
        val pi = PendingIntent.getBroadcast(context, "${item.approval}/$decision".hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Allowing needs the phone unlocked; denying is always safe.
        return NotificationCompat.Action.Builder(0, label, pi).setAuthenticationRequired(unlock).build()
    }

    private fun reply(context: Context, item: InboxItem): NotificationCompat.Action {
        val i = Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.REPLY)
            .putExtra("machine", item.machineId).putExtra("session", item.session).putExtra("notif", sessionId(item))
        val pi = PendingIntent.getBroadcast(context, "r/${item.machineId}/${item.session}".hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // mutable: RemoteInput fills it in
        val input = RemoteInput.Builder(REPLY_KEY).setLabel("Type a reply (Enter is added)").build()
        return NotificationCompat.Action.Builder(0, "Reply", pi).addRemoteInput(input)
            .setAuthenticationRequired(true).setAllowGeneratedReplies(false).build()
    }

    fun duration(secs: Double): String {
        val s = secs.toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
