# Security review — October 26, 2026

Reviewed the public source through commit `4249233e9f7b93778665328ca5a4920cec033eda` and the hardening changes accompanying this report.

## Checks and results

- Scanned all seven commits reachable from the published `main` with official Gitleaks 8.30.1, verified against the release digest, using fully redacted reporting. **No credential findings.** A separate public-history path inventory found no ADB keys, signing keys, private configurations, pairing state or recovery directories. Private local backup branches are not published.
- Reviewed browser/API authentication, Host/Origin checks, input allowlists, shell quoting, file containment, screenshot bounds and live-video ownership/cleanup. No direct unauthenticated TV-control/capture, arbitrary-command or private-file-serving route was identified in this review.
- Reviewed frontend injection sinks, constrained media paths, session lifecycle, CSP, local player provenance and GitHub workflows. Native launcher trust, private storage and protected components were also inspected.
- Queried OSV using exact direct dependency versions. Pillow 11.3.0 matched newly published advisories, including [PNG metadata parsing](https://github.com/advisories/GHSA-g4qv-2jxx-769h). Updated the supported setup to maintained Python 3.11+ and Pillow 13.0.0. The replacement version returned no advisory matches at review time. The queried versions of websockets 15.0.1, imageio-ffmpeg 0.6.0, hls.js 1.7.3, OkHttp 4.12.0, Okio 3.6.0 and JUnit 4.13.2 returned no matches. This is a package-advisory check, not a complete audit of bundled native codec binaries.

## Hardening included

- Samsung self-signed TLS now uses explicit first-pair certificate pinning for HTTPS and WebSocket requests. A changed certificate is blocked before token transmission, even when Pair is pressed; redirects are refused. Old unpinned state requires explicit approval again.
- Certificate errors block Samsung controls and stay visible through stale status responses.
- A total five-second deadline now bounds request-line, header and POST-body reads; trickled bytes cannot reset it indefinitely. The timer is canceled before TV/capture work.
- Package versions and wheel hashes are fixed in `remote/requirements.txt`; CI uses the same hash-required install. Gradle's official 8.9 ZIP checksum is pinned. The committed wrapper JAR matches Gradle's official 9.8.2 checksum (a wrapper may launch a different distribution version); CI verifies that JAR before executing the build.
- CI scans Git history for credentials using a SHA-verified Gitleaks executable with redacted output. Weekly Dependabot checks cover Actions, Gradle and Python requirements.

Focused regression tests exercise certificate mismatch before HTTPS GET/WebSocket Upgrade, refusal to replace a saved pin, legacy-state migration, redirect refusal and the frontend review gate. The normal companion regression suites remain required in CI.

## Limits

The companion still serves HTTP on a trusted LAN. A hostile network participant can intercept HTTP pairing/control/video traffic; this review does not make internet exposure safe. First Samsung pairing also depends on the trusted network and on-screen approval. GitHub account authentication, router/firewall configuration, operating-system/Fire OS vulnerabilities and every native codec binary were not comprehensively audited. No penetration test or clean scan can establish that a system is impossible to compromise. See [supported use and reporting](../SECURITY.md).

## October 27, 2026 — feature-scope update

The findings above remain a historical record for the reviewed revision. Later source adds these boundaries:

- A unique six-digit pairing code is generated once per installation and reused across restarts. It is persisted in private `remote/state/pairing-code.json`, with owned `0600` file / `0700` directory checks, bounded strict JSON parsing and atomic creation. Unsafe existing state fails closed without automatic rotation; there is no universal code.
- Optional thirty-day remembered-browser credentials use a scoped HttpOnly, SameSite Strict cookie. Only credential/target hashes and absolute expiry times are persisted. Resume creates a fresh eight-hour access session without TV actions; Forget durably revokes trust and its associated sessions. Neither credential changes nor reconnects replay TV commands. These protections do not encrypt the trusted-LAN HTTP connection.
- Optional fast key input uses a checksum-pinned scrcpy 5.0.1 control-only helper, an owner-private host socket, bounded startup and a renewable idle lease. It has no video/audio/clipboard channel and does not need root. An uncertain write is never replayed through the fallback.
- Theater 2.2.1 adds a fixed, authenticated title-request handoff, native catalog UI and a provider web embed. Payloads are bounded Unicode encoded as unpadded Base64url, with no arbitrary command, component or player URL. Default TLS verification and constrained WebView navigation remain enabled. Provider pages and service behavior are outside this repository's audit scope.
- Launcher v22 adds two bundled cinematic scenes, Annapurna and Pashupati. Wallpaper controls retain a fixed mode allowlist and protected Android receiver; they do not accept arbitrary image URLs or paths.

Focused offline fixtures cover pairing-state safety and creation races, remembered-session expiry/revocation, fixed Theater request arguments, uncertain-send behavior and fast-input ownership/cleanup. Current native checks confirmed correct Theater player opening and wake behavior without a new empty-grid crash, but recent continuous Inception and The Matrix playback stalled. Those launch checks do not establish reliable provider playback or constitute a provider security audit.

This scope note does not extend the earlier dependency-advisory or public-history scan results to every later change. Private runtime state, device addresses, authorization material, signing keys and compiled personal builds must remain outside published source and archives.
