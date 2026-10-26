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
