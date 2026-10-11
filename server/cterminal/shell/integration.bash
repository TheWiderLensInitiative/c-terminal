# C Terminal shell integration for bash. Loaded as the --rcfile of every C Terminal session.
# It loads your normal ~/.bashrc first, then reports when a command starts and ends (and the folder you're in)
# with private escape codes (OSC 6973) that the C Terminal companion reads and strips from what you see.

[ -f ~/.bashrc ] && . ~/.bashrc

# Codex keeps its conversation off the terminal's history unless it runs inline; inline, a swipe on the phone scrolls it.
if ! alias codex >/dev/null 2>&1; then alias codex='codex --no-alt-screen'; fi

__ct_b64() { printf %s "$1" | base64 -w0 2>/dev/null || printf %s "$1" | base64 | tr -d '\n'; }
__ct_at_prompt=0

__ct_preexec() {
    [ "$__ct_at_prompt" = 1 ] || return 0
    [ -n "${COMP_LINE-}" ] && return 0
    __ct_at_prompt=0
    local cmd
    cmd=$(HISTTIMEFORMAT= builtin history 1 2>/dev/null | sed 's/^ *[0-9]* *//')
    [ -n "$cmd" ] || cmd=$BASH_COMMAND
    printf '\033]6973;start;%s\007' "$(__ct_b64 "$cmd")"
}

__ct_precmd() {
    local e=$?
    printf '\033]6973;end;%s\007\033]6973;cwd;%s\007' "$e" "$(__ct_b64 "$PWD")"
    return $e
}

if declare -p preexec_functions >/dev/null 2>&1; then
    # bash-preexec (used by starship, atuin, ...) owns the DEBUG trap: plug into it instead.
    __ct_bp_preexec() { __ct_at_prompt=1; __ct_preexec; }
    __ct_bp_precmd() { __ct_precmd; __ct_at_prompt=1; }
    preexec_functions+=(__ct_bp_preexec)
    precmd_functions+=(__ct_bp_precmd)
else
    trap '__ct_preexec' DEBUG
    if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
        PROMPT_COMMAND=(__ct_precmd "${PROMPT_COMMAND[@]}" "__ct_at_prompt=1")
    else
        PROMPT_COMMAND="__ct_precmd${PROMPT_COMMAND:+;$PROMPT_COMMAND};__ct_at_prompt=1"
    fi
fi
