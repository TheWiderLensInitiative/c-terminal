"""Who is connecting? Tailscale already proved it (WireGuard), so we ask the local tailscaled and trust the answer.

No passwords, no keys, no pairing: a device is let in when it belongs to the same Tailscale user as this PC, or to
one of the logins listed in config.allowed_logins.
"""
from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass


@dataclass
class Peer:
    login: str
    device: str
    os: str
    user_id: int


async def _tailscale(*args: str) -> dict | None:
    try:
        proc = await asyncio.create_subprocess_exec(
            "tailscale", *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        out, _ = await asyncio.wait_for(proc.communicate(), 10)
    except (FileNotFoundError, asyncio.TimeoutError):
        return None
    if proc.returncode != 0:
        return None
    try:
        return json.loads(out)
    except ValueError:
        return None


@dataclass
class Self:
    user_id: int
    login: str
    name: str             # MagicDNS short name, e.g. "clara-pc"
    ips: list[str]


async def self_info() -> Self | None:
    st = await _tailscale("status", "--json")
    if not st or not st.get("Self") or st.get("BackendState") != "Running":
        return None
    me = st["Self"]
    uid = me.get("UserID", 0)
    user = (st.get("User") or {}).get(str(uid), {})
    name = (me.get("DNSName") or me.get("HostName") or "").split(".")[0]
    return Self(uid, user.get("LoginName", ""), name, list(me.get("TailscaleIPs") or []))


class Identity:
    """Caches whois answers for a minute so every request doesn't shell out."""

    def __init__(self, allowed_logins: list[str]):
        self.allowed_logins = {x.lower() for x in allowed_logins}
        self.me: Self | None = None
        self._cache: dict[str, tuple[float, Peer | None]] = {}

    async def refresh_self(self) -> Self | None:
        self.me = await self_info()
        return self.me

    async def whois(self, ip: str) -> Peer | None:
        hit = self._cache.get(ip)
        if hit and time.monotonic() - hit[0] < 60:
            return hit[1]
        data = await _tailscale("whois", "--json", ip)
        peer = None
        if data and data.get("Node"):
            node, prof = data["Node"], data.get("UserProfile") or {}
            peer = Peer(login=prof.get("LoginName", ""),
                        device=(node.get("Name") or "").split(".")[0] or (node.get("Hostinfo") or {}).get("Hostname", ""),
                        os=(node.get("Hostinfo") or {}).get("OS", ""),
                        user_id=node.get("User", 0))
        self._cache[ip] = (time.monotonic(), peer)
        return peer

    def allowed(self, peer: Peer | None) -> bool:
        if peer is None:
            return False
        if self.me and peer.user_id and peer.user_id == self.me.user_id:
            return True
        return peer.login.lower() in self.allowed_logins
