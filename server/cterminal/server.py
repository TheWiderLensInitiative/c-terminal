"""The companion's two doors.

Phone API (port 7420): bound only to this PC's Tailscale addresses. Every request is checked with `tailscale whois`:
only devices of the PC owner's Tailscale account (or config.allowed_logins) get in. Requests must carry the header
`X-CTerminal: 1` and no `Origin`, so a web page open on an allowed phone can't drive it from a browser.

Local API (127.0.0.1:7421): for `cterm` on this PC and the Claude Code / Codex hooks; needs the token in
~/.config/c-terminal/local.token.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import signal
import weakref

from aiohttp import WSMsgType, web

from . import config
from .events import EventHub, Watcher
from .sessions import Client, SessionManager
from .tailnet import Identity, Peer

log = logging.getLogger("cterminal")

HUB = web.AppKey("hub", EventHub)
SESSIONS = web.AppKey("sessions", SessionManager)
SOCKETS = web.AppKey("sockets", weakref.WeakSet)
ENTER_DELAY = 0.15   # seconds between typed text and its Enter
IDENTITY = web.AppKey("identity", Identity)
CFG = web.AppKey("cfg", config.Config)


# ------------------------------------------------------------------------------------------------- auth
def phone_auth(identity: Identity, cfg: config.Config):
    @web.middleware
    async def mw(request: web.Request, handler):
        if request.headers.get("Origin") or request.headers.get("X-CTerminal") != "1":
            raise web.HTTPForbidden(text="C Terminal only talks to its app")
        ip = request.remote or ""
        if cfg.allow_localhost and ip in ("127.0.0.1", "::1"):
            peer = Peer("localhost", "localhost", platform.system().lower(), 0)
        else:
            peer = await identity.whois(ip)
            if not identity.allowed(peer):
                log.warning("refused %s (%s)", ip, peer.login if peer else "not on the tailnet")
                raise web.HTTPForbidden(text="This device isn't allowed. Add its Tailscale login to allowed_logins.")
        request["peer"] = peer
        return await handler(request)
    return mw


def local_auth(token: str):
    @web.middleware
    async def mw(request: web.Request, handler):
        if request.headers.get("Origin") or request.headers.get("Authorization") != f"Bearer {token}":
            raise web.HTTPForbidden()
        request["peer"] = Peer("local", platform.node(), platform.system().lower(), 0)
        return await handler(request)
    return mw


# ------------------------------------------------------------------------------------------------- shared routes
async def info(request: web.Request):
    ident, sessions, hub = request.app[IDENTITY], request.app[SESSIONS], request.app[HUB]
    me = ident.me
    return web.json_response({
        "app": "c-terminal", "version": config.VERSION, "name": me.name if me else platform.node(),
        "login": me.login if me else "", "os": platform.system().lower(), "you": request["peer"].device,
        "sessions": len(sessions.sessions), "pending_approvals": len(hub.pending),
    })


async def list_sessions(request: web.Request):
    sessions = request.app[SESSIONS].sessions.values()
    return web.json_response([s.summary() for s in sorted(sessions, key=lambda s: s.created)])


async def create_session(request: web.Request):
    body = await _json(request)
    s = request.app[SESSIONS].create(
        name=_str(body.get("name")), cwd=_str(body.get("cwd")), run=_str(body.get("run")),
        cols=int(body.get("cols") or 80), rows=int(body.get("rows") or 24))
    log.info("%s started session %s (%s)", request["peer"].device, s.id, s.name)
    return web.json_response(s.summary(), status=201)


async def update_session(request: web.Request):
    s = _session(request)
    body = await _json(request)
    if _str(body.get("name")):
        s.name = body["name"].strip()[:60]
    return web.json_response(s.summary())


async def session_input(request: web.Request):
    """Type into a session without attaching (the notification's reply box uses this)."""
    s = _session(request)
    body = await _json(request)
    text = body.get("text")
    if not isinstance(text, str):
        raise web.HTTPBadRequest(text="text is required")
    mgr = request.app[SESSIONS]
    mgr.write(s, text.encode())
    if body.get("enter", True):
        # Enter as its own keypress a moment later: in one burst, apps like Claude Code read text+Enter as a
        # paste and insert a newline instead of submitting.
        asyncio.get_running_loop().call_later(ENTER_DELAY, mgr.write, s, b"\r")
    return web.json_response({"ok": True})


async def delete_session(request: web.Request):
    if not request.app[SESSIONS].kill(request.match_info["sid"]):
        raise web.HTTPNotFound()
    return web.json_response({"ok": True})


async def list_events(request: web.Request):
    hub = request.app[HUB]
    since = int(request.query.get("since", 0))
    return web.json_response({"seq": hub.seq, "events": hub.since(since), "pending": hub.pending_list()})


async def answer_approval(request: web.Request):
    body = await _json(request)
    decision = body.get("decision")
    if decision not in ("allow", "deny", "always"):
        raise web.HTTPBadRequest(text="decision must be allow, deny or always")
    ok = request.app[HUB].answer(request.match_info["aid"], decision, request["peer"].device)
    if not ok:
        raise web.HTTPGone(text="That request was already answered or has expired")
    return web.json_response({"ok": True})


async def term_ws(request: web.Request):
    """Attach to a session. Binary frames are terminal bytes both ways; text frames are JSON control messages."""
    s = _session(request)
    mgr = request.app[SESSIONS]
    ws = web.WebSocketResponse(heartbeat=25, max_msg_size=4 * 1024 * 1024)
    await ws.prepare(request)
    request.app[SOCKETS].add(ws)

    client = Client()
    cols, rows = request.query.get("cols"), request.query.get("rows")
    if cols and rows:
        mgr.resize(s, int(cols), int(rows))
    # Snapshot and join with no await in between, so no output is lost or doubled.
    client.queue.put_nowait({"type": "hello", "session": s.summary()})
    if s.scrollback:
        client.queue.put_nowait(bytes(s.scrollback))
    if s.exit_code is not None:
        client.queue.put_nowait({"type": "exit", "code": s.exit_code})
    s.clients.append(client)
    mgr.nudge_redraw(s)

    async def writer():
        while True:
            item = await client.queue.get()
            if isinstance(item, (bytes, bytearray)):
                # coalesce whatever else is queued into one frame
                buf = bytearray(item)
                while not client.queue.empty() and isinstance(client.queue._queue[0], (bytes, bytearray)) and len(buf) < 262144:
                    buf += client.queue.get_nowait()
                for i in range(0, len(buf), 65536):
                    await ws.send_bytes(bytes(buf[i:i + 65536]))
            else:
                await ws.send_json(item)

    wtask = asyncio.ensure_future(writer())
    try:
        async for msg in ws:
            if msg.type == WSMsgType.BINARY:
                mgr.write(s, msg.data)
            elif msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                except ValueError:
                    continue
                if m.get("type") == "resize":
                    mgr.resize(s, int(m.get("cols", 80)), int(m.get("rows", 24)))
                elif m.get("type") == "input":
                    mgr.write(s, str(m.get("data", "")).encode())
                elif m.get("type") == "redraw":
                    mgr.nudge_redraw(s)
    finally:
        wtask.cancel()
        if client in s.clients:
            s.clients.remove(client)
    return ws


async def events_ws(request: web.Request):
    """The phone's live feed. ?approvals=1 means "send approval requests to me" (away mode)."""
    hub = request.app[HUB]
    ws = web.WebSocketResponse(heartbeat=45)
    await ws.prepare(request)
    request.app[SOCKETS].add(ws)
    w = Watcher(request["peer"].device, request.query.get("approvals") == "1")
    since = int(request.query.get("since", hub.seq))
    for e in hub.since(since):
        w.queue.put_nowait(e)
    for p in hub.pending_list():
        w.queue.put_nowait(p)
    hub.watchers.append(w)
    log.info("%s is watching (approvals %s)", w.device, "on" if w.approvals else "off")

    async def writer():
        while True:
            await ws.send_json(await w.queue.get())

    wtask = asyncio.ensure_future(writer())
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                except ValueError:
                    continue
                if m.get("type") == "approvals":
                    w.approvals = bool(m.get("on"))
    finally:
        wtask.cancel()
        hub.watchers.remove(w)
    return ws


# ------------------------------------------------------------------------------------------------- hooks (local only)
async def hook_claude(request: web.Request):
    """Called by `cterm hook claude` from Claude Code's hooks. Returns the JSON the hook should print."""
    return await _agent_hook(request, "Claude Code")


async def hook_codex(request: web.Request):
    """Called by `cterm hook codex`: Codex's hooks (same shape as Claude Code's), or its older `notify` setting."""
    body = await _json(request)
    data = body.get("input") or {}
    if data.get("hook_event_name"):
        return await _agent_hook(request, "Codex", body)
    if data.get("type") == "agent-turn-complete":           # notify = [...] in ~/.codex/config.toml
        request.app[HUB].publish({**_agent_ctx(request, "Codex", _str(body.get("session")), data.get("cwd")),
                                  "type": "agent_done", "message": (data.get("last-assistant-message") or "")[:600]})
    return web.json_response({})


async def _agent_hook(request: web.Request, agent: str, body: dict | None = None):
    body = body if body is not None else await _json(request)
    data, session = body.get("input") or {}, _str(body.get("session"))
    hub, cfg = request.app[HUB], request.app[CFG]
    event = data.get("hook_event_name")
    ctx = _agent_ctx(request, agent, session, data.get("cwd"))

    if event == "PermissionRequest":
        if not hub.phone_takes_approvals:
            return web.json_response({})            # nobody's away: the agent asks in the terminal as usual
        tool, tinput = data.get("tool_name", ""), data.get("tool_input") or {}
        # "Always allow" adds a permission rule. Claude Code offers rules to add; Codex hooks may not return any.
        suggestions = (data.get("permission_suggestions") or []) if agent == "Claude Code" else []
        answer = await hub.ask({**ctx, "tool": tool, "summary": _tool_summary(tool, tinput),
                                "detail": json.dumps(tinput, indent=1)[:2000], "can_always": bool(suggestions)},
                               cfg.approval_timeout_seconds)
        if not answer:
            return web.json_response({})
        decision = {"behavior": "deny", "message": "Denied from the phone (C Terminal)."}
        if answer["decision"] in ("allow", "always"):
            decision = {"behavior": "allow"}
            if answer["decision"] == "always" and suggestions:
                decision["updatedPermissions"] = suggestions
        return web.json_response({"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}})

    if event == "Notification":
        kind = data.get("notification_type", "")
        if kind == "permission_prompt" and hub.phone_takes_approvals:
            return web.json_response({})            # the PermissionRequest above already went to the phone
        if kind in ("permission_prompt", "idle_prompt", "agent_needs_input", "elicitation_dialog"):
            hub.publish({**ctx, "type": "agent_waiting", "title": data.get("title") or f"{agent} needs you",
                         "message": data.get("message", "")})
    elif event == "Stop" and not data.get("stop_hook_active"):
        hub.publish({**ctx, "type": "agent_done", "message": (data.get("last_assistant_message") or "")[:600]})
    return web.json_response({})


def _agent_ctx(request, agent: str, session: str | None, cwd) -> dict:
    s = request.app[SESSIONS].sessions.get(session or "")
    return {"agent": agent, "session": s.id if s else None, "session_name": s.name if s else None,
            "cwd": cwd or (s.cwd if s else "")}


def _tool_summary(tool: str, tinput: dict) -> str:
    for key in ("command", "cmd", "file_path", "url", "pattern", "query", "path", "prompt", "description"):
        value = tinput.get(key)
        if isinstance(value, list) and value and all(isinstance(v, str) for v in value):
            if len(value) >= 3 and value[1] in ("-c", "-lc"):     # Codex: ["bash", "-lc", "npm test"]
                return value[2][:300]
            return " ".join(value)[:300]
        if isinstance(value, str) and value:
            return value[:300]
    return tool


# ------------------------------------------------------------------------------------------------- helpers
def _session(request):
    s = request.app[SESSIONS].sessions.get(request.match_info["sid"])
    if not s:
        raise web.HTTPNotFound(text="No such session (the companion may have restarted)")
    return s


async def _json(request) -> dict:
    if not request.can_read_body:
        return {}
    try:
        body = await request.json()
    except ValueError:
        raise web.HTTPBadRequest(text="expected JSON")
    return body if isinstance(body, dict) else {}


def _str(v) -> str | None:
    return v.strip() if isinstance(v, str) and v.strip() else None


def _routes(app: web.Application) -> None:
    app.router.add_get("/api/info", info)
    app.router.add_get("/api/sessions", list_sessions)
    app.router.add_post("/api/sessions", create_session)
    app.router.add_patch("/api/sessions/{sid}", update_session)
    app.router.add_delete("/api/sessions/{sid}", delete_session)
    app.router.add_post("/api/sessions/{sid}/input", session_input)
    app.router.add_get("/api/events", list_events)
    app.router.add_post("/api/approvals/{aid}", answer_approval)
    app.router.add_get("/ws/term/{sid}", term_ws)
    app.router.add_get("/ws/events", events_ws)


def _share(app: web.Application, cfg, ident, hub, mgr) -> web.Application:
    app[CFG], app[IDENTITY], app[HUB], app[SESSIONS] = cfg, ident, hub, mgr
    app[SOCKETS] = weakref.WeakSet()
    app.on_shutdown.append(_close_sockets)
    return app


async def _close_sockets(app: web.Application) -> None:
    """Live connections would otherwise hold shutdown open; phones reconnect by themselves."""
    for ws in list(app[SOCKETS]):
        await ws.close(code=1001, message=b"companion restarting")


# ------------------------------------------------------------------------------------------------- run
async def serve(cfg: config.Config) -> None:
    ident = Identity(cfg.allowed_logins)
    hub = EventHub()
    mgr = SessionManager(cfg, hub.publish)

    phone = _share(web.Application(middlewares=[phone_auth(ident, cfg)]), cfg, ident, hub, mgr)
    _routes(phone)
    local = _share(web.Application(middlewares=[local_auth(config.local_token())]), cfg, ident, hub, mgr)
    _routes(local)
    local.router.add_post("/hook/claude", hook_claude)
    local.router.add_post("/hook/codex", hook_codex)

    local_runner = web.AppRunner(local, access_log=None, shutdown_timeout=3)
    await local_runner.setup()
    await web.TCPSite(local_runner, "127.0.0.1", cfg.local_port).start()
    log.info("local API on 127.0.0.1:%d", cfg.local_port)

    phone_runner = web.AppRunner(phone, access_log=None, shutdown_timeout=3)
    await phone_runner.setup()
    bound: set[str] = set()
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)

    async def bind_tailnet():
        """Listen on the Tailscale addresses, and keep checking: Tailscale may start after us or change address."""
        warned = False
        while True:
            me = await ident.refresh_self()
            ips = [ip for ip in (me.ips if me else []) if ip not in bound]
            if cfg.allow_localhost and "127.0.0.1" not in bound:
                ips.append("127.0.0.1")
            for ip in ips:
                try:
                    await web.TCPSite(phone_runner, ip, cfg.port).start()
                    bound.add(ip)
                    log.info("phone API on %s:%d%s", ip, cfg.port, f" ({me.name})" if me else "")
                except OSError as e:
                    log.warning("can't listen on %s:%d: %s", ip, cfg.port, e)
            if not me and not warned:
                log.warning("Tailscale isn't running here yet; waiting for it")
            warned = not me
            await asyncio.sleep(30)

    btask = asyncio.ensure_future(bind_tailnet())
    try:
        await stop.wait()
    finally:
        btask.cancel()
        mgr.shutdown()
        await phone_runner.cleanup()
        await local_runner.cleanup()


def run(cfg: config.Config) -> None:
    logging.basicConfig(level=os.environ.get("CTERMINAL_LOG", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(serve(cfg))
