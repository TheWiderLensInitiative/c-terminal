"""Terminal sessions that keep running while no one is looking.

Each session is a shell on a pseudo-terminal owned by the companion. Phones (and `cterm attach` on the PC) come and
go; the shell and its scrollback stay. Output is read once here and fanned out to everyone attached.
"""
from __future__ import annotations

import asyncio
import fcntl
import os
import pty
import secrets
import signal
import struct
import termios
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable

from . import detect

INTEGRATION = Path(__file__).parent / "shell" / "integration.bash"

Emit = Callable[[dict], Awaitable[None] | None]


@dataclass
class Client:
    """Someone attached to a session. The connection handler drains the queue: bytes = output, dict = control."""
    queue: asyncio.Queue = field(default_factory=asyncio.Queue)


@dataclass
class Session:
    id: str
    name: str
    pid: int
    fd: int
    created: float = field(default_factory=time.time)
    cols: int = 80
    rows: int = 24
    cwd: str = ""
    command: str | None = None          # what's running right now (from the shell integration)
    command_started: float = 0.0
    last_output: float = field(default_factory=time.time)
    exit_code: int | None = None        # set once the shell itself has exited
    scrollback: bytearray = field(default_factory=bytearray)
    clients: list[Client] = field(default_factory=list)
    parser: detect.MarkerParser = field(default_factory=detect.MarkerParser)
    prompt_notified: str = ""          # the waiting prompt we already told the phone about
    integrated: bool = False            # True once the shell integration has said anything

    def summary(self) -> dict:
        return {
            "id": self.id, "name": self.name, "created": self.created, "cwd": self.cwd,
            "command": self.command, "running_for": time.time() - self.command_started if self.command else 0,
            "last_output": self.last_output, "alive": self.exit_code is None, "exit_code": self.exit_code,
            "attached": len(self.clients), "cols": self.cols, "rows": self.rows,
            "preview": detect.plain_tail(bytes(self.scrollback[-4000:]), 300),
        }


class SessionManager:
    def __init__(self, cfg, emit: Emit):
        self.cfg = cfg
        self.emit = emit                  # where events (finished, waiting, exited) go
        self.sessions: dict[str, Session] = {}
        self._idle_task: asyncio.Task | None = None

    # ---------------------------------------------------------------- lifecycle
    def create(self, name: str | None = None, cwd: str | None = None, run: str | None = None,
               cols: int = 80, rows: int = 24) -> Session:
        sid = secrets.token_hex(4)
        shell = self.cfg.shell
        env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", CTERMINAL_SESSION=sid,
                   CTERMINAL_PORT=str(self.cfg.local_port))
        env.pop("TMUX", None)
        argv = [shell, "--rcfile", str(INTEGRATION), "-i"] if os.path.basename(shell) == "bash" else [shell, "-i"]
        start_dir = os.path.expanduser(cwd or "~")
        if not os.path.isdir(start_dir):
            start_dir = os.path.expanduser("~")

        pid, fd = pty.fork()
        if pid == 0:   # child: become the shell
            try:
                os.chdir(start_dir)
                os.execvpe(argv[0], argv, env)
            finally:
                os._exit(127)
        _set_size(fd, cols, rows)
        os.set_blocking(fd, False)
        s = Session(sid, name or (run.split()[0] if run else os.path.basename(shell)), pid, fd,
                    cols=cols, rows=rows, cwd=start_dir)
        self.sessions[sid] = s
        asyncio.get_running_loop().add_reader(fd, self._on_readable, s)
        if run:
            os.write(fd, run.encode() + b"\r")
        self._ensure_idle_watch()
        return s

    def kill(self, sid: str) -> bool:
        s = self.sessions.get(sid)
        if not s:
            return False
        if s.exit_code is None:
            try:
                os.killpg(os.getpgid(s.pid), signal.SIGHUP)
            except ProcessLookupError:
                pass
        for c in s.clients:
            c.queue.put_nowait({"type": "exit", "code": None})
        self._close(s, remove=True)
        return True

    def shutdown(self) -> None:
        for sid in list(self.sessions):
            self.kill(sid)

    # ---------------------------------------------------------------- I/O
    def write(self, s: Session, data: bytes) -> None:
        if s.exit_code is not None:
            return
        s.prompt_notified = ""   # someone answered (or typed): a new prompt later is new news
        view = memoryview(data)
        while view:
            try:
                n = os.write(s.fd, view)
            except BlockingIOError:
                time.sleep(0.005)
                continue
            except OSError:
                return
            view = view[n:]

    def resize(self, s: Session, cols: int, rows: int) -> None:
        cols, rows = max(10, min(cols, 1000)), max(4, min(rows, 500))
        if (cols, rows) != (s.cols, s.rows) and s.exit_code is None:
            s.cols, s.rows = cols, rows
            _set_size(s.fd, cols, rows)

    def nudge_redraw(self, s: Session) -> None:
        """Make full-screen programs (Claude Code, vim, htop) repaint for someone who just attached."""
        if s.exit_code is None:
            _set_size(s.fd, s.cols, max(4, s.rows - 1))
            asyncio.get_running_loop().call_later(0.05, lambda: s.exit_code is None and _set_size(s.fd, s.cols, s.rows))

    def _on_readable(self, s: Session) -> None:
        try:
            data = os.read(s.fd, 65536)
        except BlockingIOError:
            return
        except OSError:
            data = b""
        if not data:
            self._shell_exited(s)
            return
        shown, marks = s.parser.feed(data)
        for kind, value in marks:
            self._on_mark(s, kind, value)
        if shown:
            s.last_output = time.time()
            s.scrollback += shown
            over = len(s.scrollback) - self.cfg.scrollback_bytes
            if over > 0:
                cut = s.scrollback.find(b"\n", over)   # trim at a line start so escape codes aren't cut in half
                del s.scrollback[:cut + 1 if cut >= 0 else over]
            for c in s.clients:
                c.queue.put_nowait(shown)

    def _on_mark(self, s: Session, kind: str, value: str) -> None:
        s.integrated = True
        if kind == "start":
            s.command, s.command_started = value.strip(), time.time()
            s.prompt_notified = ""
        elif kind == "end" and s.command is not None:
            took = time.time() - s.command_started
            cmd, s.command = s.command, None
            try:
                code = int(value)
            except ValueError:
                code = 0
            if took >= self.cfg.notify_after_seconds:
                self._emit({"type": "finished", "session": s.id, "session_name": s.name, "command": cmd,
                            "exit_code": code, "seconds": round(took, 1),
                            "tail": detect.plain_tail(bytes(s.scrollback[-4000:]), 400)})
        elif kind == "cwd":
            s.cwd = value

    def _shell_exited(self, s: Session) -> None:
        try:
            _, status = os.waitpid(s.pid, os.WNOHANG)
            code = os.waitstatus_to_exitcode(status) if status else 0
        except ChildProcessError:
            code = 0
        s.exit_code = code
        self._close(s, remove=False)
        for c in s.clients:
            c.queue.put_nowait({"type": "exit", "code": code})
        self._emit({"type": "exited", "session": s.id, "session_name": s.name, "exit_code": code})

    def _close(self, s: Session, remove: bool) -> None:
        try:
            asyncio.get_running_loop().remove_reader(s.fd)
        except (ValueError, OSError):
            pass
        try:
            os.close(s.fd)
        except OSError:
            pass
        if remove:
            self.sessions.pop(s.id, None)
            try:
                os.waitpid(s.pid, os.WNOHANG)
            except ChildProcessError:
                pass

    # ---------------------------------------------------------------- "is it waiting for me?"
    def _ensure_idle_watch(self) -> None:
        if not self._idle_task or self._idle_task.done():
            self._idle_task = asyncio.ensure_future(self._idle_watch())

    async def _idle_watch(self) -> None:
        while self.sessions:
            await asyncio.sleep(1.0)
            now = time.time()
            for s in list(self.sessions.values()):
                if s.exit_code is not None or now - s.last_output < 1.5:
                    continue
                # With the shell integration we only look while a command runs (so the shell prompt itself never
                # counts). Claude Code's own prompts come through its hooks instead, more precisely.
                if s.integrated and (s.command is None or _is_hooked_agent(s.command)):
                    continue
                hit = detect.waiting_prompt(bytes(s.scrollback[-4000:]))
                if hit and hit[1] != s.prompt_notified:
                    s.prompt_notified = hit[1]
                    self._emit({"type": "waiting", "session": s.id, "session_name": s.name, "reason": hit[0],
                                "prompt": hit[1], "command": s.command})

    def _emit(self, event: dict) -> None:
        r = self.emit(event)
        if asyncio.iscoroutine(r):
            asyncio.ensure_future(r)


def _is_hooked_agent(command: str) -> bool:
    first = os.path.basename(command.split()[0]) if command.split() else ""
    return first == "claude"


def _set_size(fd: int, cols: int, rows: int) -> None:
    try:
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    except OSError:
        pass
