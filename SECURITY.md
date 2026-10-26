# Security

Publishing this source does not publish a working connection to a TV. Each installation needs its own private device configuration, ADB authorization and browser pairing code; Samsung has separate on-TV approval. No shared password, private signing key or preconfigured authorization belongs in this repository.

## Supported scope

The companion is a **trusted home-LAN HTTP service**, not an internet server. Do not expose it through router forwarding or public tunnels. Someone with the pairing code can control the configured TV and view its captured screen. An attacker able to intercept or change traffic on the LAN can steal HTTP credentials or screen content; Host/Origin checks and CSP do not encrypt that traffic. Use `--bind 127.0.0.1` for computer-only access. Phone use requires an explicitly trusted local network. Remote access over an untrusted network needs a separately configured, authenticated encrypted connection.

Use a maintained Python 3.11+ interpreter and the hash-pinned `remote/requirements.txt`. Keep the computer, browsers, Fire OS, Android Platform Tools and installed codec runtime patched. Optional tools come from their official sources; the scrcpy server and Gradle distribution have pinned checksums. Hash verification establishes artifact identity, not the absence of vulnerabilities.

## Boundaries in this implementation

- Browser controls, typing, snapshots and video start/stop require a random paired session; pairing attempts are limited. POSTs also require the exact local Origin, JSON and allowlisted payloads. Media uses a session-scoped HttpOnly cookie and an expiring owner lease. No arbitrary shell command, device address, executable or file path is accepted over HTTP. Request reads have a total deadline as well as socket and concurrency limits.
- Public assets are confined to `remote/dist/`. Private configuration and Samsung state are separately owned files with mode `0600`. ADB and Android signing material, runtime state, logs and backups are excluded from Git. Live buffers are private and removed on Stop/expiry.
- Samsung connections pin the certificate accepted during explicit first pairing. Both HTTPS and WebSocket peers are checked before application requests or saved-token transmission. Changed certificates are refused; legacy unpinned tokens require explicit pairing. Initial trust still requires the correct TV and trusted LAN.
- The Android launcher stores pairing privately with backup disabled. Its wallpaper receiver requires Android's privileged `DUMP` permission. The screensaver service requires `BIND_DREAM_SERVICE`.
- GitHub jobs use pinned action commits and read-only repository permissions. The secret-scanning job scans history with redacted output; Dependabot proposes dependency updates. These checks do not replace reviewing a change before merging it.

## Review and reporting

The [October 26 review](docs/SECURITY-REVIEW.md) records what was checked, changes made and limits. A clean scan is evidence about that revision, not a guarantee of permanent security.

Never post PINs, tokens, private device files, keys or screenshots containing personal information in a public issue. Use GitHub's private vulnerability reporting for this repository if available; otherwise ask the maintainer for a private reporting channel without including the sensitive details. If a real credential is ever committed, revoke/rotate it immediately; deleting the file in a later commit does not remove it from history.
