"""cterm: the C Terminal companion's command line.

  cterm serve                 run the companion (the installer sets this up to start at login)
  cterm status                is it running, and which address does the phone use
  cterm ls                    list sessions
  cterm new [--run CMD]       start a session (and attach to it here)
  cterm attach ID             continue a session at the PC that you started on the phone (Ctrl-] to detach)
  cterm kill ID               end a session
  cterm hooks install|remove  connect Claude Code to C Terminal (approvals + notifications on the phone)
  cterm hook claude|codex     (called by Claude Code / Codex; not for you)
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

from . import config


def _local(method: str, path: str, body: dict | None = None, timeout: float = 5):
    cfg = config.Config.load()
    req = urllib.request.Request(f"http://127.0.0.1:{cfg.local_port}{path}", method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {config.local_token()}",
                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"null")


def _down() -> None:
    sys.exit("The C Terminal companion isn't running. Start it with: systemctl --user start c-terminal")


# ------------------------------------------------------------------------------------------------- hooks
def hook(kind: str) -> None:
    """Forward an agent's hook event to the companion; print what it says the agent should get back.

    Never get in the agent's way: if the companion is down or anything fails, print nothing and exit 0.
    """
    try:
        raw = sys.argv[3] if kind == "codex" and len(sys.argv) > 3 else sys.stdin.read()
        data = json.loads(raw or "{}")
        cfg = config.Config.load()
        wait = cfg.approval_timeout_seconds + 15 if data.get("hook_event_name") == "PermissionRequest" else 5
        out = _local("POST", f"/hook/{kind}", {"input": data, "session": os.environ.get("CTERMINAL_SESSION")}, wait)
        if out:
            print(json.dumps(out))
    except Exception:
        pass


CLAUDE_SETTINGS = os.path.expanduser("~/.claude/settings.json")
_MARK = "cterm hook claude"


def hooks_install(remove: bool = False) -> None:
    exe = os.path.expanduser("~/.local/bin/cterm")
    cmd = f"{exe if os.path.exists(exe) else 'cterm'} hook claude"
    settings = {}
    if os.path.exists(CLAUDE_SETTINGS):
        with open(CLAUDE_SETTINGS) as f:
            settings = json.load(f)
    hooks = settings.setdefault("hooks", {})
    for event in ("PermissionRequest", "Notification", "Stop"):
        groups = [g for g in hooks.get(event, []) if not any(_MARK in h.get("command", "") for h in g.get("hooks", []))]
        if not remove:
            timeout = config.Config.load().approval_timeout_seconds + 30 if event == "PermissionRequest" else 10
            groups.append({"hooks": [{"type": "command", "command": cmd, "timeout": timeout}]})
        if groups:
            hooks[event] = groups
        else:
            hooks.pop(event, None)
    if not hooks:
        settings.pop("hooks")
    os.makedirs(os.path.dirname(CLAUDE_SETTINGS), exist_ok=True)
    tmp = CLAUDE_SETTINGS + ".cterm-tmp"
    with open(tmp, "w") as f:
        json.dump(settings, f, indent=2)
        f.write("\n")
    os.replace(tmp, CLAUDE_SETTINGS)
    if remove:
        print("Removed the C Terminal hooks from Claude Code.")
    else:
        print(f"Claude Code now tells C Terminal when it needs you ({CLAUDE_SETTINGS}).")
        print("For Codex, add this line to ~/.codex/config.toml:")
        print(f'  notify = ["{exe if os.path.exists(exe) else "cterm"}", "hook", "codex"]')


# ------------------------------------------------------------------------------------------------- attach
def attach(sid: str) -> None:
    import asyncio
    import shutil
    import signal
    import termios
    import tty

    import aiohttp

    cfg = config.Config.load()
    if not sys.stdin.isatty():
        sys.exit("attach needs a terminal")

    async def go():
        cols, rows = shutil.get_terminal_size()
        url = f"http://127.0.0.1:{cfg.local_port}/ws/term/{sid}?cols={cols}&rows={rows}"
        headers = {"Authorization": f"Bearer {config.local_token()}"}
        loop = asyncio.get_running_loop()
        async with aiohttp.ClientSession() as http:
            try:
                ws = await http.ws_connect(url, headers=headers)
            except aiohttp.WSServerHandshakeError as e:
                sys.exit("No such session." if e.status == 404 else f"Couldn't attach ({e.status}).")
            except aiohttp.ClientConnectorError:
                _down()
            out = sys.stdout.buffer

            def on_input():
                data = os.read(sys.stdin.fileno(), 4096)
                if not data or b"\x1d" in data:            # Ctrl-] detaches
                    asyncio.ensure_future(ws.close())
                    return
                asyncio.ensure_future(ws.send_bytes(data))

            def on_winch():
                c, r = shutil.get_terminal_size()
                asyncio.ensure_future(ws.send_json({"type": "resize", "cols": c, "rows": r}))

            loop.add_reader(sys.stdin.fileno(), on_input)
            loop.add_signal_handler(signal.SIGWINCH, on_winch)
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.BINARY:
                    out.write(msg.data)
                    out.flush()
                elif msg.type == aiohttp.WSMsgType.TEXT:
                    m = json.loads(msg.data)
                    if m.get("type") == "exit":
                        break
            loop.remove_reader(sys.stdin.fileno())

    old = termios.tcgetattr(sys.stdin)
    try:
        tty.setraw(sys.stdin)
        asyncio.run(go())
    finally:
        termios.tcsetattr(sys.stdin, termios.TCSADRAIN, old)
        print("\r\n[detached from C Terminal session; it keeps running]")


# ------------------------------------------------------------------------------------------------- main
def main() -> None:
    p = argparse.ArgumentParser(prog="cterm", description="C Terminal companion: your PC's terminal, on your phone.")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("serve")
    sub.add_parser("status")
    sub.add_parser("ls")
    n = sub.add_parser("new")
    n.add_argument("--run")
    n.add_argument("--name")
    n.add_argument("--detached", action="store_true")
    a = sub.add_parser("attach")
    a.add_argument("id")
    k = sub.add_parser("kill")
    k.add_argument("id")
    h = sub.add_parser("hooks")
    h.add_argument("action", choices=["install", "remove"])
    hk = sub.add_parser("hook")
    hk.add_argument("kind", choices=["claude", "codex"])
    hk.add_argument("payload", nargs="?")
    args = p.parse_args()

    if args.cmd == "serve":
        from .server import run
        run(config.Config.load())
    elif args.cmd == "hook":
        hook(args.kind)
    elif args.cmd == "hooks":
        hooks_install(remove=args.action == "remove")
    elif args.cmd in ("status", "ls", "new", "kill"):
        try:
            if args.cmd == "status":
                i = _local("GET", "/api/info")
                print(f"C Terminal {i['version']} is running.")
                print(f"  On the phone, add this PC as:  {i['name'] or '(Tailscale not connected)'}")
                print(f"  Sessions: {i['sessions']}   Waiting approvals: {i['pending_approvals']}")
            elif args.cmd == "ls":
                for s in _local("GET", "/api/sessions"):
                    state = s["command"] or ("idle" if s["alive"] else f"exited {s['exit_code']}")
                    print(f"{s['id']}  {s['name']:<14} {state[:40]:<40} {s['cwd']}")
            elif args.cmd == "new":
                s = _local("POST", "/api/sessions", {"run": args.run, "name": args.name})
                if args.detached or not sys.stdin.isatty():
                    print(s["id"])
                else:
                    attach(s["id"])
            elif args.cmd == "kill":
                _local("DELETE", f"/api/sessions/{args.id}")
        except urllib.error.HTTPError as e:
            sys.exit(f"{e.code}: {e.read().decode(errors='replace')}")
        except (urllib.error.URLError, ConnectionError):
            _down()
    elif args.cmd == "attach":
        attach(args.id)
    else:
        p.print_help()


if __name__ == "__main__":
    main()
