# C Terminal privacy

C Terminal has no servers, no accounts and no analytics. It is two pieces you run yourself: the companion on your
PC and the app on your phone. They talk to each other directly, inside your own Tailscale network. This page lists
every place your data goes.

## On your PC

`~/.config/c-terminal` holds your settings (`config.json`) and a random token (`local.token`) that only programs on
the PC itself can read; the Claude Code and Codex hooks use it to reach the companion.

Your terminal sessions, their scrollback and the list of recent events live **in the companion's memory only**.
They are never written to disk, and they are gone when the companion stops or the PC restarts.

The companion asks the Tailscale program on your PC who each connecting device is (`tailscale whois`), and lets in
only devices on your own Tailscale account. It does not contact any other service.

## On your phone

- The list of computers you added (a name, a Tailscale name or address, a port) and your settings (away mode,
  which notifications you want, font size).
- Android backup is off for the app, so none of this is copied to Google Drive or to a new phone.

The app does not store what you type or what your terminals show; it only draws what the PC sends while a session
is open.

Permissions: **Internet** (to reach your PC), **Network state** (to reconnect as soon as your signal returns),
**Notifications**, **Run in the foreground** (a quiet notification that keeps one small connection to each PC open
so alerts and approvals arrive on time) and **Run at startup** (to reconnect after a reboot).

## In between

All traffic goes from your phone to your PC inside Tailscale, which encrypts it end to end with WireGuard. The
companion listens only on your PC's Tailscale addresses, never on your home network or the internet. Tailscale's
own coordination service sees which of your devices exist, as it always does, but not what they send each other.
See Tailscale's privacy policy: https://tailscale.com/privacy-policy

## Claude Code and Codex

If you connect them (`cterm hooks install`, Codex's `notify` setting), they tell the companion when they need
permission, need you, or finish a turn. That includes the command or file they want to use and their last message,
which the companion shows on your phone. It goes nowhere else.

## Contact

Questions: cterminal@thewiderlens.info · Security issues: see [SECURITY.md](../SECURITY.md)
