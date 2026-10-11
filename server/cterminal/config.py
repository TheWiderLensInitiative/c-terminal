"""Settings and file locations. Everything lives in ~/.config/c-terminal (override with $CTERMINAL_HOME)."""
from __future__ import annotations

import json
import os
import secrets
from dataclasses import asdict, dataclass, field
from pathlib import Path

VERSION = "0.1.5"
HOME = Path(os.environ.get("CTERMINAL_HOME", Path.home() / ".config" / "c-terminal"))
CONFIG_FILE = HOME / "config.json"
LOCAL_TOKEN_FILE = HOME / "local.token"   # guards the loopback API that the Claude Code / Codex hooks use


@dataclass
class Config:
    port: int = 7420                      # phone API, bound to this PC's Tailscale addresses only
    local_port: int = 7421                # hook API, bound to 127.0.0.1 only
    # Tailscale logins allowed in besides the owner of this PC (the owner is always allowed).
    allowed_logins: list[str] = field(default_factory=list)
    shell: str = os.environ.get("SHELL", "/bin/bash")
    scrollback_bytes: int = 2_000_000     # kept per session so a phone can reattach and see everything
    notify_after_seconds: float = 10.0    # only commands that ran at least this long send "finished"
    approval_timeout_seconds: int = 300   # after this the request falls back to the prompt in the terminal
    # Dev/test only: let 127.0.0.1 use the phone API without a Tailscale identity.
    allow_localhost: bool = False

    @classmethod
    def load(cls) -> "Config":
        cfg = cls()
        if CONFIG_FILE.exists():
            data = json.loads(CONFIG_FILE.read_text())
            for k, v in data.items():
                if hasattr(cfg, k):
                    setattr(cfg, k, v)
        return cfg

    def save(self) -> None:
        HOME.mkdir(parents=True, exist_ok=True)
        CONFIG_FILE.write_text(json.dumps(asdict(self), indent=2) + "\n")


def local_token() -> str:
    """The secret the hooks send to the loopback API, created on first use (mode 600)."""
    HOME.mkdir(parents=True, exist_ok=True)
    if not LOCAL_TOKEN_FILE.exists():
        fd = os.open(LOCAL_TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(32))
    return LOCAL_TOKEN_FILE.read_text().strip()
