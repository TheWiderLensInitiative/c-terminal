#!/usr/bin/env bash
# C Terminal companion installer and updater (Linux).
#
#   From anywhere:   curl -fsSL https://raw.githubusercontent.com/TheWiderLensInitiative/c-terminal/main/install.sh | bash
#   From the repo:   ./install.sh            (run it again any time to update)
#   Remove it:       ./install.sh --uninstall
#
# It gets Python if needed (via uv, no sudo), installs the companion, starts it at login, and can connect Claude Code
# so its approvals and "I'm done" messages reach your phone.
set -euo pipefail
trap 'printf "\n\033[31mThe installer stopped at line %s (%s).\033[0m Run it again; if it keeps failing, open an issue at\nhttps://github.com/TheWiderLensInitiative/c-terminal/issues with this output.\n" "$LINENO" "$BASH_COMMAND" >&2' ERR

REPO="${CTERMINAL_REPO:-https://github.com/TheWiderLensInitiative/c-terminal}"
APK_URL="https://github.com/TheWiderLensInitiative/c-terminal/releases/latest"

say()  { printf '\n\033[1;36m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
ask()  {   # ask "Question?" -> 0 for yes. Reads the terminal even when piped from curl; no terminal = no.
    local a=""
    { : < /dev/tty; } 2>/dev/null || return 1
    printf '    %s [Y/n] ' "$1" > /dev/tty
    read -r a < /dev/tty || return 1
    [[ -z "$a" || "$a" =~ ^[Yy] ]]
}

[ "$(uname -s)" = Linux ] || die "The companion runs on Linux for now (macOS is planned)."

# --- where the code lives: this repo, or a fresh clone in ~/c-terminal ---------------------------------------------
HERE=""   # piped from curl there is no script file: always use ~/c-terminal then
if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; fi
if [ -n "$HERE" ] && [ -f "$HERE/server/cterminal/server.py" ]; then
    ROOT="$HERE"
else
    ROOT="$HOME/c-terminal"
fi
SERVER="$ROOT/server"
UNIT="$HOME/.config/systemd/user/c-terminal.service"
BIN="$HOME/.local/bin/cterm"

if [ "${1:-}" = "--uninstall" ]; then
    say "Removing C Terminal"
    systemctl --user disable --now c-terminal 2>/dev/null && ok "stopped the companion" || true
    rm -f "$UNIT" && systemctl --user daemon-reload 2>/dev/null || true
    [ -x "$BIN" ] && "$BIN" hooks remove >/dev/null 2>&1 && ok "disconnected Claude Code" || true
    rm -f "$BIN"
    rm -rf "$SERVER/.venv" && ok "removed the Python environment"
    echo "    Your settings are kept in ~/.config/c-terminal (delete it to forget everything). The code is in $ROOT."
    exit 0
fi

say "Getting C Terminal"
if [ "$ROOT" = "$HOME/c-terminal" ]; then
    command -v git >/dev/null || die "git is needed: install it (for example: sudo apt install git) and run this again."
    if [ -d "$ROOT/.git" ]; then git -C "$ROOT" pull -q --ff-only && ok "updated $ROOT"
    else git clone -q "$REPO" "$ROOT" && ok "downloaded to $ROOT"; fi
else
    ok "using this folder ($ROOT)"
fi

say "Setting up Python"
cd "$SERVER"
if ! command -v uv >/dev/null 2>&1; then
    if ! { command -v python3 >/dev/null && python3 -c 'import sys, venv, ensurepip; sys.exit(sys.version_info < (3, 11))' 2>/dev/null; }; then
        echo "    Installing uv (a small tool that downloads the right Python into your home folder; no sudo)"
        curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null
        export PATH="$HOME/.local/bin:$PATH"
    fi
fi
if command -v uv >/dev/null 2>&1; then
    [ -x .venv/bin/python ] || uv venv -q --python 3.12 .venv
    uv pip install --python .venv/bin/python -q -r requirements.txt
else
    [ -x .venv/bin/python ] || python3 -m venv .venv
    .venv/bin/pip install -q --upgrade pip
    .venv/bin/pip install -q -r requirements.txt
fi
ok "Python $(.venv/bin/python -c 'import platform; print(platform.python_version())') with everything the companion needs"

mkdir -p "$(dirname "$BIN")"
cat > "$BIN" <<SH
#!/usr/bin/env bash
cd "$SERVER" && exec "$SERVER/.venv/bin/python" -m cterminal "\$@"
SH
chmod +x "$BIN"
ok "installed the cterm command"

say "Starting the companion"
if command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
    mkdir -p "$(dirname "$UNIT")"
    sed -e "s#%h/c-terminal/server#$SERVER#g" systemd/c-terminal.service > "$UNIT"
    systemctl --user daemon-reload
    systemctl --user enable -q c-terminal
    systemctl --user restart c-terminal
    ok "running, and it starts when you log in"
    if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]; then
        if ask "Keep it running after you log out, and start it at boot (recommended for a PC you reach from your phone)?"; then
            loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER"
            ok "it now runs even when nobody is logged in"
        fi
    fi
else
    nohup "$BIN" serve > "$HOME/.local/share/c-terminal.log" 2>&1 &
    warn "no systemd here, so it's running now but won't start at login. Start it later with: cterm serve"
fi

say "Network"
if ! command -v tailscale >/dev/null 2>&1; then
    warn "Tailscale isn't installed. The phone reaches this PC only through Tailscale: https://tailscale.com/download"
    warn "Install it, sign in, and the companion picks it up by itself."
elif ! tailscale status >/dev/null 2>&1; then
    warn "Tailscale is installed but not connected. Run: sudo tailscale up"
else
    ok "Tailscale is connected"
    if command -v ufw >/dev/null && sudo -n true 2>/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
        sudo ufw allow in on tailscale0 to any port 7420 proto tcp comment "C Terminal" >/dev/null && ok "opened port 7420 on Tailscale only"
    elif command -v ufw >/dev/null; then
        echo "    If you use a firewall (ufw), allow the phone in with: sudo ufw allow in on tailscale0 to any port 7420 proto tcp"
    fi
fi

say "Claude Code and Codex"
if [ -d "$HOME/.claude" ] || [ -d "$HOME/.codex" ] || command -v claude >/dev/null 2>&1 || command -v codex >/dev/null 2>&1; then
    if grep -qs "cterm hook" "$HOME/.claude/settings.json" "$HOME/.codex/hooks.json"; then
        "$BIN" hooks install >/dev/null && ok "already connected (hooks refreshed)"
    elif ask "Connect Claude Code and Codex? Their approvals (in away mode) and 'done' messages will reach your phone."; then
        "$BIN" hooks install | sed 's/^/    /'
    else
        echo "    Skipped. Connect them later with: cterm hooks install"
    fi
else
    echo "    Neither Claude Code nor Codex is installed here. If you add one later, run: cterm hooks install"
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do "$BIN" status >/dev/null 2>&1 && break; sleep 0.5; done
NAME="$(tailscale status --json 2>/dev/null | "$SERVER/.venv/bin/python" -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].split(".")[0])' 2>/dev/null || true)"
say "Done"
echo "    1. Get the app on your phone: $APK_URL"
echo "    2. Make sure the phone is on Tailscale with the same account as this PC."
echo "    3. In the app, tap “Add computer” and type:  ${NAME:-the name Tailscale shows for this PC}"
echo
echo "    cterm status   check on it        cterm ls          list sessions"
echo "    cterm attach   continue a phone session here (Ctrl-] to leave it running)"
