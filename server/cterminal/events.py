"""The inbox: things worth telling the phone about, and approvals that wait for an answer from it."""
from __future__ import annotations

import asyncio
import secrets
import time
from collections import deque
from dataclasses import dataclass, field


@dataclass
class Watcher:
    device: str
    approvals: bool                       # this phone wants approval requests ("away mode")
    queue: asyncio.Queue = field(default_factory=asyncio.Queue)


@dataclass
class Approval:
    id: str
    event: dict
    future: asyncio.Future


class EventHub:
    def __init__(self, keep: int = 300):
        self.events: deque[dict] = deque(maxlen=keep)
        self.seq = 0
        self.watchers: list[Watcher] = []
        self.pending: dict[str, Approval] = {}

    def publish(self, event: dict) -> dict:
        self.seq += 1
        event = {"seq": self.seq, "time": time.time(), **event}
        self.events.append(event)
        for w in self.watchers:
            w.queue.put_nowait(event)
        return event

    def since(self, seq: int) -> list[dict]:
        if seq > self.seq:   # the phone remembers numbers from before this companion restarted: send everything
            seq = 0
        return [e for e in self.events if e["seq"] > seq]

    @property
    def phone_takes_approvals(self) -> bool:
        return any(w.approvals for w in self.watchers)

    # ---------------------------------------------------------------- approvals
    async def ask(self, event: dict, timeout: float) -> dict | None:
        """Publish an approval request and wait for a phone to answer. None = nobody answered in time."""
        aid = secrets.token_hex(6)
        fut = asyncio.get_running_loop().create_future()
        self.pending[aid] = Approval(aid, event, fut)
        self.publish({**event, "type": "approval", "approval": aid})
        try:
            return await asyncio.wait_for(asyncio.shield(fut), timeout)
        except asyncio.TimeoutError:
            return None
        finally:
            ap = self.pending.pop(aid, None)
            if ap and not fut.done():
                fut.cancel()
            answer = fut.result() if fut.done() and not fut.cancelled() else None
            self.publish({"type": "approval_closed", "approval": aid,
                          "decision": (answer or {}).get("decision", "expired"),
                          "by": (answer or {}).get("by", ""), "session": event.get("session")})

    def answer(self, aid: str, decision: str, by: str) -> bool:
        ap = self.pending.get(aid)
        if not ap or ap.future.done():
            return False
        ap.future.set_result({"decision": decision, "by": by})
        return True

    def pending_list(self) -> list[dict]:
        return [{**a.event, "type": "approval", "approval": a.id} for a in self.pending.values()]
