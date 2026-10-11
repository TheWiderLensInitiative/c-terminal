import asyncio
import base64
import json
import os
import socket

import aiohttp
import pytest

from cterminal import config, detect


# ------------------------------------------------------------------------------------------------- detection
def test_marker_parser_strips_and_reports_even_when_split():
    cmd = base64.b64encode(b"sleep 1").decode()
    stream = f"hello\x1b]6973;start;{cmd}\x07world\x1b]6973;end;3\x07!".encode()
    for cut in range(1, len(stream)):
        p = detect.MarkerParser()
        a, ea = p.feed(stream[:cut])
        b, eb = p.feed(stream[cut:])
        assert a + b == b"helloworld!", cut
        assert ea + eb == [("start", "sleep 1"), ("end", "3")]


def test_other_escape_codes_pass_through():
    p = detect.MarkerParser()
    shown, ev = p.feed(b"\x1b]0;title\x07\x1b[31mred\x1b]")
    shown2, _ = p.feed(b"2;x\x07")
    assert shown + shown2 == b"\x1b]0;title\x07\x1b[31mred\x1b]2;x\x07" and not ev


@pytest.mark.parametrize("line", [
    "Do you want to continue? [Y/n] ", "Proceed (y/n)?", "[sudo] password for jorge: ",
    "Are you sure you want to continue connecting (yes/no/[fingerprint])? ", "Enter passphrase for key '/x':",
    "Press ENTER to continue", "Overwrite existing file? [y/N]",
])
def test_waiting_prompts(line):
    assert detect.waiting_prompt(b"some output\r\n\x1b[1m" + line.encode() + b"\x1b[0m")


def test_previews_drop_all_escape_codes():
    raw = b"\x1b[?1h\x1b=\x1b(Bhello \x1b[1;32mworld\x1b[0m\x1b>\x1b7\r\n\x1b]0;title\x07done"
    assert detect.plain_tail(raw) == "hello world\ndone"


@pytest.mark.parametrize("line", ["$ ", "Compiling foo v1.0", "Downloaded 3 files", "what is this?"])
def test_not_waiting(line):
    assert detect.waiting_prompt(line.encode()) is None


# ------------------------------------------------------------------------------------------------- end to end
def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.fixture
async def companion(tmp_path, monkeypatch):
    async for c in _companion(tmp_path, monkeypatch):
        yield c[:3]


async def _companion(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "HOME", tmp_path)
    monkeypatch.setattr(config, "LOCAL_TOKEN_FILE", tmp_path / "local.token")
    from cterminal import server, tailnet

    async def no_tailscale():
        return None
    monkeypatch.setattr(tailnet, "self_info", no_tailscale)
    cfg = config.Config(port=_free_port(), local_port=_free_port(), allow_localhost=True, shell="/bin/bash",
                        notify_after_seconds=0.5, approval_timeout_seconds=5)
    task = asyncio.ensure_future(server.serve(cfg))
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", cfg.port), 0.1).close()
            break
        except OSError:
            await asyncio.sleep(0.05)
    phone = aiohttp.ClientSession(f"http://127.0.0.1:{cfg.port}", headers={"X-CTerminal": "1"})
    local = aiohttp.ClientSession(f"http://127.0.0.1:{cfg.local_port}",
                                  headers={"Authorization": f"Bearer {config.local_token()}"})
    yield phone, local, cfg, task
    await phone.close()
    await local.close()
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def _read_until(ws, needle: bytes, timeout=8.0) -> bytes:
    buf = b""
    async with asyncio.timeout(timeout):
        while needle not in buf:
            msg = await ws.receive()
            if msg.type == aiohttp.WSMsgType.BINARY:
                buf += msg.data
            elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                break
    return buf


async def _event(ws, kind: str, timeout=8.0) -> dict:
    async with asyncio.timeout(timeout):
        while True:
            e = await ws.receive_json()
            if e.get("type") == kind:
                return e


async def test_requests_from_browsers_are_refused(companion):
    phone, _, cfg = companion
    async with aiohttp.ClientSession() as bare:
        r = await bare.get(f"http://127.0.0.1:{cfg.port}/api/info")
        assert r.status == 403
        r = await bare.get(f"http://127.0.0.1:{cfg.port}/api/info", headers={"X-CTerminal": "1", "Origin": "https://evil.example"})
        assert r.status == 403
        r = await bare.get(f"http://127.0.0.1:{cfg.local_port}/api/info")
        assert r.status == 403
    assert (await phone.get("/api/info")).status == 200


async def test_session_runs_reattaches_and_reports_finished_commands(companion):
    phone, _, _ = companion
    events = await phone.ws_connect("/ws/events")
    s = await (await phone.post("/api/sessions", json={"name": "test"})).json()
    ws = await phone.ws_connect(f"/ws/term/{s['id']}?cols=100&rows=30")
    await ws.send_bytes(b"echo hi-$((40+2)); sleep 0.8; echo done-$((1+1))\r")
    out = await _read_until(ws, b"done-2")
    assert b"hi-42" in out and b"6973" not in out          # markers never reach the screen
    fin = await _event(events, "finished")
    assert fin["command"].startswith("echo hi") and fin["exit_code"] == 0 and fin["session"] == s["id"]
    await ws.close()

    # a second device attaching later sees the whole history
    ws2 = await phone.ws_connect(f"/ws/term/{s['id']}")
    assert b"hi-42" in await _read_until(ws2, b"done-2")
    await ws2.close()
    sessions = await (await phone.get("/api/sessions")).json()
    assert sessions[0]["id"] == s["id"] and sessions[0]["alive"]
    assert sessions[0]["cwd"] == "~"   # started in the home folder, shown short
    assert (await phone.delete(f"/api/sessions/{s['id']}")).status == 200
    await events.close()


async def test_waiting_for_input_is_noticed(companion):
    phone, _, _ = companion
    events = await phone.ws_connect("/ws/events")
    s = await (await phone.post("/api/sessions", json={"run": "read -p 'Delete everything? [y/N] ' x; echo got-$x"})).json()
    e = await _event(events, "waiting")
    assert e["session"] == s["id"] and "[y/N]" in e["prompt"]
    ws = await phone.ws_connect(f"/ws/term/{s['id']}")
    r = await phone.post(f"/api/sessions/{s['id']}/input", json={"text": "n"})   # the notification's reply box
    assert r.status == 200
    assert b"got-n" in await _read_until(ws, b"got-n")
    await ws.close()
    await events.close()


async def test_claude_permission_goes_to_the_phone_and_back(companion):
    phone, local, _ = companion
    hook_input = {"hook_event_name": "PermissionRequest", "tool_name": "Bash", "cwd": "/tmp",
                  "tool_input": {"command": "npm install express"},
                  "permission_suggestions": [{"type": "addRules", "behavior": "allow", "destination": "localSettings",
                                              "rules": [{"toolName": "Bash", "ruleContent": "npm install:*"}]}]}

    # nobody in away mode: answer at once with nothing, so Claude asks in the terminal
    r = await local.post("/hook/claude", json={"input": hook_input})
    assert await r.json() == {}

    events = await phone.ws_connect("/ws/events?approvals=1")
    await asyncio.sleep(0.1)
    pending = asyncio.ensure_future(local.post("/hook/claude", json={"input": hook_input}))
    ask = await _event(events, "approval")
    assert ask["summary"] == "npm install express" and ask["can_always"]
    r = await phone.post(f"/api/approvals/{ask['approval']}", json={"decision": "always"})
    assert r.status == 200
    out = await (await pending).json()
    d = out["hookSpecificOutput"]["decision"]
    assert d["behavior"] == "allow" and d["updatedPermissions"] == hook_input["permission_suggestions"]
    closed = await _event(events, "approval_closed")
    assert closed["decision"] == "always"

    # deny, and answering twice is refused
    pending = asyncio.ensure_future(local.post("/hook/claude", json={"input": hook_input}))
    ask = await _event(events, "approval")
    await phone.post(f"/api/approvals/{ask['approval']}", json={"decision": "deny"})
    assert (await (await pending).json())["hookSpecificOutput"]["decision"]["behavior"] == "deny"
    assert (await phone.post(f"/api/approvals/{ask['approval']}", json={"decision": "allow"})).status == 410
    await events.close()


async def test_agent_done_and_codex(companion):
    phone, local, _ = companion
    events = await phone.ws_connect("/ws/events")
    await local.post("/hook/claude", json={"input": {"hook_event_name": "Stop", "last_assistant_message": "All tests pass."}})
    e = await _event(events, "agent_done")
    assert e["agent"] == "Claude Code" and e["message"] == "All tests pass."
    await local.post("/hook/codex", json={"input": {"type": "agent-turn-complete", "last-assistant-message": "Done."}})
    e = await _event(events, "agent_done")
    assert e["agent"] == "Codex"
    await events.close()


def test_hooks_install_is_idempotent_and_removable(tmp_path, monkeypatch):
    from cterminal import __main__ as cli
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"model": "x", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "mine"}]}]}}))
    monkeypatch.setattr(cli, "CLAUDE_SETTINGS", str(path))
    cli.hooks_install()
    cli.hooks_install()
    s = json.loads(path.read_text())
    assert len(s["hooks"]["Stop"]) == 2 and len(s["hooks"]["PermissionRequest"]) == 1
    cli.hooks_install(remove=True)
    s = json.loads(path.read_text())
    assert s == {"model": "x", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "mine"}]}]}}


async def test_stops_promptly_with_phones_connected(tmp_path, monkeypatch):
    gen = _companion(tmp_path, monkeypatch)
    phone, _, _, task = await gen.__anext__()
    events = await phone.ws_connect("/ws/events")
    s = await (await phone.post("/api/sessions", json={})).json()
    term = await phone.ws_connect(f"/ws/term/{s['id']}")
    task.cancel()
    async with asyncio.timeout(6):
        await asyncio.gather(task, return_exceptions=True)
    async with asyncio.timeout(3):
        while (await term.receive()).type not in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
            pass
    await events.close()
    await term.close()
    await gen.aclose()


def test_events_after_a_restart_are_not_skipped():
    from cterminal.events import EventHub
    hub = EventHub()
    hub.publish({"type": "finished"})
    hub.publish({"type": "waiting"})
    # the phone last saw seq 57 from the companion's previous run
    assert [e["type"] for e in hub.since(57)] == ["finished", "waiting"]
    assert [e["type"] for e in hub.since(1)] == ["waiting"]
