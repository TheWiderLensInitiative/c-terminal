"""Reading the terminal stream: the shell integration's markers, and prompts that are waiting for a person."""
from __future__ import annotations

import base64
import re

_MARK = b"\x1b]6973;"
_MAX_MARK = 16_384   # a marker longer than this isn't ours; let it through


class MarkerParser:
    """Pulls our OSC 6973 markers out of a byte stream (they may arrive split across reads).

    feed() returns (bytes to show, [(kind, value), ...]). Kinds: "start" (command text), "end" (exit code), "cwd".
    """

    def __init__(self) -> None:
        self._pending = b""

    def feed(self, data: bytes) -> tuple[bytes, list[tuple[str, str]]]:
        data = self._pending + data
        self._pending = b""
        out = bytearray()
        events: list[tuple[str, str]] = []
        i = 0
        while True:
            j = data.find(b"\x1b]6973;", i)
            if j < 0:
                # keep a trailing partial "\x1b]69..." for the next read
                tail = data[i:]
                for k in range(min(len(_MARK) - 1, len(tail)), 0, -1):
                    if _MARK.startswith(tail[-k:]):
                        out += tail[:-k]
                        self._pending = tail[-k:]
                        break
                else:
                    out += tail
                break
            out += data[i:j]
            end = data.find(b"\x07", j)
            if end < 0:
                if len(data) - j > _MAX_MARK:
                    out += data[j:]
                else:
                    self._pending = data[j:]
                break
            body = data[j + len(_MARK):end].decode("utf-8", "replace")
            kind, _, value = body.partition(";")
            if kind in ("start", "cwd"):
                try:
                    value = base64.b64decode(value).decode("utf-8", "replace")
                except ValueError:
                    value = ""
            events.append((kind, value))
            i = end + 1
        return bytes(out), events


_ANSI = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[@-Z\\-_]")

# A line ending like one of these, with no output after it for a moment, is a program waiting for its person.
_WAITING = [
    (re.compile(r"[\[(]\s*y(es)?\s*/\s*no?\s*[\])]\s*[:?]?\s*$", re.I), "Asking yes or no"),
    (re.compile(r"\(yes/no(/\[fingerprint\])?\)\??\s*$", re.I), "Asking yes or no"),
    (re.compile(r"\bpassword( for [^:]+)?:\s*$", re.I), "Asking for a password"),
    (re.compile(r"\bpassphrase\b.*:\s*$", re.I), "Asking for a passphrase"),
    (re.compile(r"\b(press|hit) (enter|return|any key)\b", re.I), "Waiting for a key press"),
    (re.compile(r"\b(continue|proceed|overwrite|replace)\b[^\n]{0,60}\?\s*(\[[^\]]*\])?\s*$", re.I), "Asking to continue"),
]


def plain_tail(raw: bytes, limit: int = 600) -> str:
    """The end of the screen as plain text (escape codes removed), for detection and previews."""
    text = _ANSI.sub(b"", raw[-4000:]).decode("utf-8", "replace")
    text = text.replace("\r\n", "\n")
    # a lone \r means the line was redrawn: keep what was drawn last
    lines = [ln.rsplit("\r", 1)[-1] for ln in text.split("\n")]
    return "\n".join(lines)[-limit:]


def waiting_prompt(raw_tail: bytes) -> tuple[str, str] | None:
    """(reason, the prompt line) when the last thing printed looks like a question, else None."""
    lines = [ln.strip() for ln in plain_tail(raw_tail).split("\n") if ln.strip()]
    if not lines:
        return None
    last = lines[-1][-200:]
    for rx, reason in _WAITING:
        if rx.search(last):
            return reason, last
    return None
