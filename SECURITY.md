# Security

C Terminal gives a phone a shell on your computer, so its whole design is about who can reach that shell.

**Who gets in**
- The phone API (port 7420) listens **only on this PC's Tailscale addresses**, never on your LAN or the internet.
- Every request is checked with `tailscale whois`: the connecting device must belong to **the same Tailscale user as
  the PC** (or a login you list in `allowed_logins`). Tailscale has already authenticated the device with WireGuard
  keys, so there is no password to guess or token to steal.
- Requests must carry the header `X-CTerminal: 1` and **no** `Origin` header. Browsers always send `Origin` and can't
  add custom headers across sites without a CORS preflight (which the companion never answers), so a web page open on
  your phone or laptop can't drive your terminal.
- The hook API (port 7421) listens on `127.0.0.1` only and needs the random token in
  `~/.config/c-terminal/local.token` (mode 600).

**On the phone**
- "Allow", "Always allow" and "Reply" on notifications require the phone to be unlocked. "Deny" doesn't.
- Notifications are marked private: if your lock screen is set to hide sensitive content, their text stays hidden there.
- The app keeps no passwords or keys. Its only data is the list of computers, and that's excluded from backups.

**What it is not**
- Anyone who can unlock your phone can use your terminal, just like anyone at your unlocked PC. Use a screen lock.
- Sessions run as your user, with your permissions. Away mode doesn't sandbox Claude Code; it only moves the question
  to your phone.

**Reporting a problem:** please open a private advisory at
https://github.com/TheWiderLensInitiative/c-terminal/security/advisories/new rather than a public issue.
