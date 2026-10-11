package info.thewiderlens.cterminal.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What the companions told us, for the app's screens. WatchService fills it. */
data class InboxItem(val machineId: String, val machineName: String, val event: JsonObject) {
    fun str(key: String) = event[key]?.jsonPrimitive?.contentOrNull
    val type get() = str("type") ?: ""
    val approval get() = str("approval")
    val session get() = str("session")
}

object Inbox {
    private val _pending = MutableStateFlow<List<InboxItem>>(emptyList())
    val pending = _pending.asStateFlow()            // approvals waiting for an answer
    private val _recent = MutableStateFlow<List<InboxItem>>(emptyList())
    val recent = _recent.asStateFlow()              // newest first
    private val _online = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val online = _online.asStateFlow()

    fun add(item: InboxItem) {
        when (item.type) {
            "approval" -> _pending.update { list -> list.filterNot { it.approval == item.approval } + item }
            "approval_closed" -> _pending.update { list -> list.filterNot { it.approval == item.approval } }
        }
        if (item.type in setOf("finished", "waiting", "agent_waiting", "agent_done", "exited", "approval")) {
            _recent.update { (listOf(item) + it).take(60) }
        }
    }

    fun answered(approval: String) = _pending.update { list -> list.filterNot { it.approval == approval } }

    fun setOnline(machineId: String, on: Boolean) {
        _online.update { it + (machineId to on) }
        if (!on) _pending.update { list -> list.filterNot { it.machineId == machineId } }
    }

    fun forget(machineId: String) {
        _online.update { it - machineId }
        _pending.update { list -> list.filterNot { it.machineId == machineId } }
        _recent.update { list -> list.filterNot { it.machineId == machineId } }
    }
}
