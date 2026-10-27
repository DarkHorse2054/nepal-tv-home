# Local Fire TV remote

The UI/UX Pro Max redesign uses a warm charcoal Himalayan theme, a sculpted glass directional pad, a compact floating app dock, and clear Samsung / Fire TV control groups. App names appear on focus, hover or press; small screens reflow to named tiles. Typing, Sources, pairing and settings share matching sheets with visible in-sheet action feedback. See [the design system](DESIGN.md) for guidance, tokens and verification limits.

The companion server supports macOS/Linux with POSIX private-file checks; a phone or browser on any platform can use it on the same LAN. Keep the computer awake, the Fire TV powered, and ADB authorized. This repository does not include personal addresses, saved authorizations, or a configured TV.

The phone interface includes a compact glass directional pad, navigation/playback keys, six app shortcuts and sheets for typing, Samsung Sources, Theater title requests, Live view and connection settings. It supports a private per-install pairing code, thirty-day remembered browsers, optional fast keys and supported-phone haptics. Live view offers continuous video or separate refreshed snapshots, each started explicitly. It distinguishes an unavailable Mac, an offline Fire TV and an expired pairing session. Reconnect checks the configured target without replaying commands. On especially narrow screens, app shortcuts use two rows to preserve touch targets.

## Configure before starting

Requires a maintained Python 3.11+ release, an IANA timezone database, and Android Platform Tools. Put `adb` on PATH or supply its absolute path with `--adb`. Use a virtual environment and the reviewed package versions with verified artifact hashes:

```sh
python3 -m venv .venv
.venv/bin/python -m pip --isolated install --require-hashes --only-binary=:all: --index-url https://pypi.org/simple -r remote/requirements.txt
export NEPAL_REMOTE_PYTHON="$PWD/.venv/bin/python"
```

The pinned file includes Pillow 13.0.0, websockets 15.0.1 and imageio-ffmpeg 0.6.0. These enable the optional screenshot, Samsung and video features. Keep the running interpreter and packages patched; Python 3.9 is no longer the supported setup. [Security scope and reporting](../SECURITY.md) explains the trusted-LAN boundary.

From the repository root:

```sh
umask 077
mkdir -p remote/state
chmod 700 remote/state
cp remote/config.example.json remote/state/config.json
chmod 600 remote/state/config.json
```

Edit `remote/state/config.json`:

- `fire_tv.host`: your explicitly selected RFC1918 IPv4 address; the example is deliberately empty.
- `fire_tv.port`: exactly `5555`.
- `fire_tv.model`: the exact model reported by that device's `ro.product.model`, rather than a guessed model.
- `fire_tv.name`: the label shown in the webpage.
- `clocks`: two valid IANA timezone identifiers and their display labels; the secondary calendar is `gregorian` or `bikram_sambat`.
- `samsung`: `null` to disable Samsung controls, or an object with your Samsung's explicit private `host`, port `8002`, exact API `modelName`, and optional `source_labels`.
- `weather`: optional, or `null` to disable. To enable, set `latitude` (−90 to 90), `longitude` (−180 to 180), a printable `label`, and `temperature_unit` (`fahrenheit` or `celsius`). Forecast dates use `clocks.local_timezone`; timezones alone do not identify a city.

For example, an optional Samsung target has this shape, using your own values:

```json
{"host":"192.168.1.101","port":8002,"model":"YOUR_VERIFIED_SAMSUNG_MODEL","source_labels":{"hdmi1":"Fire TV","hdmi2":"Console"}}
```

A weather configuration looks like this, using your own city coordinates:

```json
{"latitude":12.3,"longitude":-45.6,"label":"Example city","temperature_unit":"celsius"}
```

Legacy schema-version-1 configurations remain valid without a `weather` member and do not contact a weather provider. The supplied example sets it to `null` and has no personal location default.

Targets accept literal addresses in `10.0.0.0/8`, `172.16.0.0/12`, or `192.168.0.0/16`. Hostnames, URLs, public addresses, arbitrary serials, alternate device ports and command strings are rejected. The model is checked against the actual device before controls are sent. Configuration has a strict schema; custom shell commands or app components cannot be added.

Source labels are optional display names for `hdmi1` through `hdmi4`, at most 64 printable characters each. They cannot change the fixed key mapping or declare that a physical port exists. Omitted labels use HDMI 1-4. Renaming a source does not change the target fingerprint or discard its saved Samsung authorization. Use names matching your actual cables; the example does not establish where your Fire TV is connected.

There is no automatic device discovery or setup wizard in this initial repository base. Missing or invalid configuration exits with instructions before starting a listener or contacting a device. The private config must be an owned regular file with mode `0600`, outside public `dist/`.

## Start

```sh
./remote/start.sh
# Or use an explicit private configuration and ADB executable:
./remote/start.sh --config /absolute/path/config.json --adb /absolute/path/adb
```

The default configuration is `remote/state/config.json`. The default listener is `0.0.0.0:8765`; `--bind 127.0.0.1` limits it to this computer and `--port 8766` changes its port. `--allow-host` adds one explicit local IP or `.local` Host name if route-based local-address selection misses your desired interface. It does not change either configured TV target.

Open the printed LAN URL from your phone and enter the six-digit PIN shown in the terminal. The companion generates a unique six-digit PIN once per installation and saves it in private `remote/state/pairing-code.json`. Updates and restarts reuse that code. Keep this private state when updating or moving your setup; it must stay out of Git and release archives. Keep the companion running while using the remote.

### Remember this browser

**Remember this device for 30 days** is checked when pairing. Leave it checked on your own phone or laptop: the same browser can reconnect without the PIN after its eight-hour access session expires or the companion restarts. The page obtains a fresh access token automatically; it never replays a previous TV command or starts Live view automatically. Bookmark and reuse the same remote URL. A different browser/address, cleared browser data or private browsing can require pairing again. Changing the configured TV target also invalidates saved trust.

Uncheck Remember on a shared device. Its access token stays in browser session storage and expires after eight hours or when the server stops. To remove saved trust, open **Settings → Forget this device** while connected. A successful Forget deletes that browser's remembered credential and revokes its associated access sessions; pairing is then required again. If the server cannot confirm Forget, reconnect and retry.

Remembered trust has an absolute thirty-day expiry; reconnecting does not extend it. Its credential lives in a host-only HttpOnly, SameSite Strict cookie scoped to `/api/session/`. JavaScript stores only a non-secret remembered flag in local storage; neither the PIN nor a persistent bearer token is stored there. The companion persists credential/target hashes and expiry times in private `remote/state/trusted-devices.json`, with a `0600` file inside a `0700` directory. Git ignores all of `remote/state/`; keep it out of release archives too. To revoke all remembered browsers locally, stop the companion, remove that private file and restart; every browser must pair with the saved PIN. To deliberately change the PIN, stop the companion, remove only private `remote/state/pairing-code.json` and restart. This does not revoke remembered browsers; remove `trusted-devices.json` too if that is intended. Invalid or unsafe pairing-code state stops startup rather than silently changing the code.

This is an HTTP service for a trusted home LAN. It does not encrypt browser traffic against an attacker on that network; a public static host cannot provide its local ADB bridge. Do not publish its listener through a public tunnel or router forwarding.

ADB uses your environment's normal authorization/key location. No SDK, private keys, or machine-specific runtime is bundled. If the Fire TV's DHCP address changes, update the private config and restart; the server does not scan for a replacement.

## Play on Theater

Choose **Play on Theater** beside Live view. Enter a movie title or a show's season and episode, optionally using your phone keyboard's microphone, then press **Search & play**. For example: `play Inception (2010)`, `play Breaking Bad s2e3` or `search for Dune`. No microphone permission or speech service is added by the remote.

Theater resolves the title on the TV using its provider catalog. Explicit Play/Watch requests open a single exact title match, checking a supplied year; ambiguous matches stay on TV for selection. A bare title or Search/Find request shows results without autoplaying. Use a parenthesized year, as in `play Inception (2010)`. Requested episodes are validated before launch. Theater 2.2.1 must be installed separately. The companion sends Fire TV's Wake key before the title request so its clock screensaver cannot cover the app; this does not power on a separate Samsung panel.

These are title commands, not model-generated recommendations. Recommendation AI is not configured; no cloud AI API or model key is used. The phone and companion keep no request history and do not log request bodies. The provider receives catalog and playback requests.

Requests allow 1–160 Unicode codepoints on one line, excluding control characters and malformed Unicode. The field locks during submission. Success says **Request sent to Theater. Check the TV.** It confirms transport delivery, not a matching title, stream availability or playback. An uncertain response keeps the draft and asks you to check the TV before retrying. There is no automatic resend; closing or backgrounding abandons unsent queued requests. Closing discards the phone draft.

### Known issues

The latest API-25 checks opened the correct movie players from phone requests and woke Fire TV without a new native crash. Empty/loading grids now reopen safely, and explicit zero-result responses show No matches. Continuous Inception and The Matrix playback still stalled in recent checks. This provider playback problem remains unresolved; request delivery, search results and a loaded player are separate from confirmed playback. See [Theater's current limits](../framextv/README.md#known-issues).

## Other app shortcuts

The **Other apps** section in Settings includes **Open Theater**. Install the separate [Theater app](../framextv/README.md) on your own Fire TV first. Theater provides native movie/show search and season/episode selection, then opens the frameXTV provider's embedded web player inside the app. Amazon Silk is not used. The fixed `app.nepal.framextv/.MainActivity` component remains the same; the remote does not accept arbitrary URLs or extract media. Opening the app or sending player commands does not verify playback.

## Haptic feedback

Open Settings to turn **Haptic feedback** on or off and use **Test haptics** without sending a TV command. On supported phones, accepted Fire TV, app, text, wallpaper and Samsung actions request one subtle eight-millisecond pulse immediately on the press. A held button pulses only once; background checks and network responses stay quiet. The pulse acknowledges the press, not TV delivery. Vibration failure never blocks or retries a TV command.

The preference stays in this browser as a non-secret setting. Haptics default off with reduced motion unless explicitly enabled. Availability depends on the phone, browser and system vibration settings. Safari on iPhone does not expose the general Vibration API; the page disables the setting and explains this when the API is absent. Visual press feedback remains available. See [Vibration API support](https://developer.mozilla.org/en-US/docs/Web/API/Navigator/vibrate#browser_compatibility). Browser checks use controlled vibration stubs; a physical pulse requires testing on a supported phone.

## Optional Samsung panel and Source controls

Samsung support uses the local Tizen TLS WebSocket endpoint on port 8002. Compatible models must report the configured exact model and token-auth support. In the webpage, choose Connect Samsung and accept Allow on your own television. This approval is separate from the webpage PIN.

Authorizations and the approved Samsung certificate SHA-256 are saved as `remote/state/samsung-<target-fingerprint>.json` with mode `0600` in a `0700` directory. The filename and file contents bind the token to the configured host and model. Tokens are never returned to the webpage or logged. Keep all `state/` contents out of commits and release archives.

The TV's self-signed certificate is trusted on first use only during explicit pairing on your trusted LAN. HTTPS identity checks and the WebSocket handshake must present that same certificate; the saved token is never sent before the peer passes this check. A changed certificate is refused even when Pair is pressed again. Existing unpinned token files require explicit pairing after this update. Review a certificate-change error before deliberately removing the private pairing file and pairing the correct TV again; do not bypass it blindly. No certificate or token is supplied by the repository.

Status reads local token/runtime authorization state only; it is not a live Samsung connection probe. Rejected authorization keeps the connection UI unpaired until explicit pairing succeeds. Pairing is limited to one request per minute, with a separate Samsung lock; Fire TV controls keep working during the approval wait.

The compact Samsung strip sits above Fire TV controls and remains usable when Fire TV is offline. Volume up/down, mute and explicit Power off are fixed clicks; no power toggle, power-on, Wake-on-LAN or automatic retry is included. A sleeping panel may be unreachable, so use its physical remote to wake it for testing.

Sources opens a sheet with Open Sources and Up/Down/Left/Right/OK/Back navigation. Close/Escape dismisses the phone sheet; Back on Samsung sends the TV's Return key. **Experimental** discrete HDMI 1-4 keys remain in the API/helper only, outside the normal interface. These keys are not established as working on the recorded TU7000 firmware, and 3/4 may not correspond to physical sockets. No Source-menu macro, current-input readback or Samsung screenshot API is implemented. See [the observed-test procedure](SAMSUNG-CONTROLS.md).

The UI says **Last sent**, never Active. This memory-only entry changes only after a successful WebSocket write and resets when the server restarts. It is not a TV acknowledgement, an input-selection result or persistent device state. Status reads this entry and the local token file without opening a Samsung connection.

The standalone helper also uses your configuration:

```sh
python3 -B remote/samsung.py --config remote/state/config.json pair
python3 -B remote/samsung.py --config remote/state/config.json connect
python3 -B remote/samsung.py --config remote/state/config.json nav source
# One observed experimental test at a time; check the panel after sending:
python3 -B remote/samsung.py --config remote/state/config.json nav hdmi1
```

## API and behavior

**Type on TV** sends 1–160 printable ASCII characters to the currently selected television field. English letters, numbers, spaces and standard symbols are supported. Unicode, emoji, line breaks and control characters are rejected before device I/O; unusually complex symbol sequences can require smaller parts. Text is quoted for Android's remote shell and is not logged, echoed by the API or retained by this companion. The receiving TV app/platform handles its own storage. The phone field locks while sending, clears after acknowledgement and retains an uncertain draft until the user checks the TV or closes the sheet. Closing discards the draft. There is no automatic text retry. Delete and Enter are separate one-key actions.

Every POST requires the existing Host checks, same-origin Origin and JSON. Pairing routes use the terminal PIN; `/api/session/resume` uses the remembered HttpOnly cookie. Other API routes, including Forget and all TV controls, require the paired webpage bearer token. The cookie alone cannot control the TV. No CORS access is granted.

| Request | Exact body | Meaning |
| --- | --- | --- |
| POST /api/pair | `{"pin":"123456"}` | Legacy unremembered eight-hour browser session |
| POST /api/session/pair | `{"pin":"123456","remember":true}` | Eight-hour session and optional thirty-day browser trust |
| POST /api/session/resume | `{}` | Fresh eight-hour bearer session using the cookie; no TV I/O |
| POST /api/session/forget | `{}` | Revoke browser trust and its associated sessions; clear the cookie |
| GET /api/status | none | Fire TV availability, configured labels/dates, local Samsung authorization state |
| POST /api/key | `{"key":"home"}` | One allowlisted Fire TV key |
| POST /api/app | `{"app":"paramount"}` | Launch one fixed known app component |
| POST /api/text | `{"text":"Kathmandu"}` | Send bounded text once to the selected field |
| POST /api/theater | `{"request":"play Inception (2010)"}` | `{ok:true,state:"request_sent"}` after fixed Theater request dispatch; not playback confirmation |
| POST /api/reconnect | `{}` | Check the configured Fire TV; returns `{ok:true,online:boolean}` |
| POST /api/live/start | `{}` | Start one owned, leased continuous-video producer |
| GET /api/live/status | none | Read the paired session's video state; does not start or renew it |
| POST /api/live/keepalive | `{}` | Renew the active owner's video lease |
| POST /api/live/stop | `{}` | Stop the owner's producer and remove its rolling video buffer |
| POST /api/preview/frame | `{}` | One bounded JPEG capture in Snapshots mode |
| POST /api/samsung/pair | `{}` | Explicit bounded Samsung approval request |
| POST /api/samsung/key | `{"key":"source"}` | One allowlisted Samsung panel/navigation/test click |

Both pairing paths accept only `{pin}` or `{pin,remember:boolean}`. Their responses include `token`, `expiresIn` and `remembered`. The webpage uses `/api/session/pair` so its scoped cookie accompanies the request; re-pairing with an existing valid cookie revokes its remembered credential before replacing or clearing it. Resume does not extend the cookie deadline and is limited to twenty requests per minute per IP and 120 globally. Unsafe or unreadable trust state fails closed with `503`; no credential is acknowledged before its durable write succeeds.

Fire TV keys: `up`, `down`, `left`, `right`, `ok`, `home`, `back`, `menu`, `play_pause`, `rewind`, `fast_forward`, `wake`, `sleep`, `enter`, `delete`. App aliases: `netflix`, `youtube`, `disney`, `paramount`, `hulu`, `npl`, `espn`, `framextv`. The NPL alias targets the independent `app.nepal.npltv` app, which must be installed separately and requires the user's own provider account/subscription. The `framextv` alias targets Theater's native search and season/episode interface, with playback in the frameXTV provider's embedded web player. Components can differ across firmware or app versions; a launch failure does not install an app or retry an arbitrary component.

Text errors include `invalid_text_length`, `unsupported_text_character`, `split_text_required` and `text_send_uncertain`. The last means some or all text may have arrived, so check the TV before retrying. Queued inputs expire after a short deadline and are invalidated on blur, hiding, disconnect or sheet transitions; releasing a held key cancels its queued repeats. Reconnect never replays previous controls.

A manifest and original local icon support browser bookmarking. The plain-HTTP LAN service has no service worker or offline-control support. Access sessions still expire after eight hours or a server restart; remembered browsers can obtain a fresh session as described above. A manifest alone does not preserve authorization.

Samsung keys: `source`, `up`, `down`, `left`, `right`, `ok`, `back`, `volume_up`, `volume_down`, `mute`, `power_off`, `hdmi1`, `hdmi2`, `hdmi3`, `hdmi4`. Raw `KEY_*` strings, arbitrary command bodies and generic `power` are rejected. Pairing can return authorized or pending; pending is not approval. Sends return `state:"sent"`, `key`, `sentAt`, `experimental` and `inputVerified:false`; HDMI tests have `experimental:true`. Status adds `sourceLabels` and `lastSent`, while `directHdmi` and `sourceReadback` remain false. The backend permits at most eight Fire TV controls or four Samsung controls per second, using separate locks. This is a request ceiling, not a recommended hardware cadence. Run observed HDMI tests one at a time, leaving several seconds to see the result. Failed inputs are not automatically retried.

Home sends native Android key code 3. This remote does not change the default launcher, create a redirector, or root a device. `ok:true` means the transport accepted a command, not that the TV's visible result or DRM playback was independently verified.

### Optional fast key input

With the verified scrcpy 5.0.1 server available, authenticated online status checks prepare a control-only connection in the background. Repeated keys then use that existing connection instead of starting Android's `input` program for every press. The temporary helper has no video, audio, clipboard synchronization, automatic wake or keep-awake behavior. Its host-side ADB tunnel uses a socket inside a private `0700` directory, and it retires after sixty seconds without a visible remote's status checks or key presses. Shutdown removes its owned process, tunnel, directory and temporary device JAR. Preparation sends no key events.

Run the explicit [pinned-server setup](#optional-video-setup) to install this optional helper, or select an existing verified copy using `--scrcpy-server /absolute/path/scrcpy-server-v5.0.1`. Fast keys do not require FFmpeg or an active Live view. If the helper is absent or not ready, a key uses the existing Android input command. A write that might already have reached the helper is never replayed through that fallback. App launches, text and wallpapers retain their existing command paths. Use `--no-fast-input` to select legacy keys for troubleshooting.

Date status includes `localDate`, `localTime`, `localLabel`, `secondaryDate`, `secondaryTime` and `secondaryLabel`. The historical `sanMateo`, `nepal` and `kathmanduTime` fields remain aliases for existing frontend compatibility; they use your configured zones. The bundled BS calendar ends at BS 2083 and falls back to Gregorian beyond its table. Its license is retained in `data/NEPALI-CALENDAR-LICENSE.txt`.

## Live view

Choose **Live view** below the Tools row. The sheet defaults to **Live video**; opening it does not start a producer or capture the screen. Select **Start live video** to receive a continuous H.264 stream from the configured Fire TV through the companion computer. The video and D-pad, Back and Home remain together. Navigation also works before playback and while the player is paused.

The tested API 25 Fire TV provides **video only, without audio**. Expect a delay of several seconds rather than an instant remote monitor. Keep the companion computer awake, the Fire TV reachable and ADB authorized. Live view shows the **Fire TV stick's output only**. Samsung apps, Samsung's native AirPlay receiver and other HDMI sources are outside it; protected video and secure windows may be blank.

The player waits for a ready stream and reports Playing only after the browser's actual playback event. Buffering remains explicit. If autoplay is refused, use **Play video** or the video's native controls. The locally bundled hls.js 1.7.3 player is preferred when MSE/Managed Media Source is supported, including compatible Safari versions. Native HLS is the fallback on browsers such as older iOS Safari. No CDN is required.

The local HLS player targets two one-second segments behind the live edge, seeks forward if playback falls more than four segments behind, and retains six seconds of past video in the browser buffer. Resuming a delayed player catches up; native HLS also receives a four-second seekable-edge guard while playing. These are player buffer targets, not a promise of two-second TV-to-phone latency: capture, relay and networking add delay. Snapshots uses its separate, slower capture interval.

**Stop live view**, closing or switching sheets, changing to Snapshots, hiding/leaving the page, disconnecting or ending the session stops the owned producer and clears the player. Start never resumes automatically. A pending Start is followed by its own Stop before the interface permits a new Start. The companion has a thirty-second owner lease, renewed every ten seconds only by an active, visible viewer, and a fifteen-minute maximum session. If a browser disappears before Stop can arrive, the lease expires and cleanup follows.

### Optional video setup

Remote controls and Snapshots do not require the live-video tools. For continuous video, install the pinned official scrcpy 5.0.1 server explicitly:

```sh
python3 remote/setup_live_tools.py --download
```

The helper downloads the official release, verifies its pinned SHA-256 and stores it privately at `remote/state/tools/scrcpy-server-v5.0.1`. The running companion never downloads a tool automatically. Keep this file and all other `state/` contents out of Git and public archives. Use `--scrcpy-server /absolute/path/scrcpy-server-v5.0.1` to select another copy of that same verified version.

Live video also needs an FFmpeg executable with `libx264` encoding. Use an existing `ffmpeg` on PATH, pass `--ffmpeg /absolute/path/ffmpeg`, or install the optional bundled executable into the same Python environment that runs the companion:

```sh
python3 -m pip --isolated install --require-hashes --only-binary=:all: --index-url https://pypi.org/simple -r remote/requirements.txt
./remote/start.sh
# Explicit tool locations are also supported:
./remote/start.sh --scrcpy-server /absolute/path/scrcpy-server-v5.0.1 --ffmpeg /absolute/path/ffmpeg
```

The server starts scrcpy through the authorized ADB shell, without root or installing a persistent Android application. It receives H.264 over a local ADB tunnel. Local FFmpeg decodes bounded 1280 × 720 YUV frames; single-threaded rawvideo output avoids holding a completed frame while a quiet screen waits for another update. The relay retains only the latest frame and feeds a second FFmpeg process at twenty frames per second. Repeating the last decoded frame keeps a still Home screen live without requesting screenshots. The encoder creates one-second HLS segments in a five-entry rolling playlist. Temporary directories are private and media files use mode `0600`; Stop/expiry removes the owned processes, tunnel, device-side temporary server and video buffer. This is a temporary playback buffer rather than a saved recording. Snapshot frames remain memory-only as described below.

### Video authorization and verification

The four `/api/live/` control routes use the existing paired bearer session; POST bodies must be exactly `{}` and satisfy the same Host/Origin checks. Responses report `starting`, `ready`, `stopped` or `error`, `videoOnly:true`, and a relative playlist path while active. Status reads do not renew the lease. Only the owner can stop or renew its stream; no arbitrary device, executable argument, media URL or output path is accepted through the API.

Playlist and segment requests use an HttpOnly, same-origin cookie scoped to that live session. They are not public static files, and bearer tokens never appear in media URLs. Ordinary status polling and media reads do not start a producer. The existing trusted-LAN HTTP requirements still apply.

Actual H.264 capture and continuous 1280 × 720 browser playback have been verified on the tested AFTMM using the local Hls.js player. A moving local TV page, advancing playback beyond one minute, Home and D-pad navigation, phone-width layouts and complete Stop cleanup were checked. A twelve-second observation advanced the playback timeline by 12.1 seconds. After the quiet-frame fix, an additional check verified a screensaver clock change across a full minute; a paired browser's Right press appeared as a changed app dock in about three seconds. This single navigation sample is not a guarantee for other content or networks. Native iPhone/Safari playback and protected-service playback remain unverified. Automated checks cover authorization, bounded media, leases, cleanup, player lifecycle, stale responses and changed image content arriving while the source is idle.

### Snapshots

Within **Live view**, choose **Snapshots → Start preview**. Selecting the mode alone does not capture anything. It shows refreshed screenshots of the Fire TV, with the same D-pad, Back and Home inside the sheet. Requests run one at a time; the next starts three seconds after the previous request finishes, so capture time adds to that interval. The displayed time marks the start of the capture attempt, alongside its transfer latency. This mode refreshes still images and has no audio.

**Stop preview** pauses updates and retains the last image, labeled as paused. Closing the sheet, opening another sheet, hiding/leaving the page, losing the connection or ending the session stops requests and clears the image. Starting again is explicit. A request already accepted by the server may finish within its bounded deadline after the browser cancels; there is no continuing recorder or server-side capture loop.

Preview requires the companion computer to stay awake, Fire TV to be reachable, and its existing ADB authorization to remain valid. It captures the **Fire TV stick's output only**: Samsung apps, Samsung's native AirPlay receiver and other HDMI sources are outside it. Protected video or secure windows may be blank. Images can include visible messages, account details or other screen content; only start preview when you intend to view that screen.

Snapshots additionally needs [Pillow](https://pillow.readthedocs.io/en/stable/installation/basic-installation.html) in the Python environment that runs the companion:

```sh
python3 -m pip --isolated install --require-hashes --only-binary=:all: --index-url https://pypi.org/simple -r remote/requirements.txt
```

Use that environment's Python executable if it differs from `python3`. Pillow is optional: without it, pairing, remote controls and the other tools still work, while Snapshots reports `preview_dependency_missing`. Continuous video uses its separate FFmpeg/scrcpy tools.

Each frame is an authenticated same-origin `POST /api/preview/frame` with the exact JSON body `{}` and the existing bearer session header. GET and HEAD never capture. The response is `image/jpeg` with `Cache-Control: no-store`, `X-Preview-Captured-At` in UTC and `X-Preview-Capture-Ms`. Session tokens stay in headers, never image URLs. No capture is triggered by ordinary status polling.

The backend checks the configured TV identity for every capture, without reconnecting or waking it. It permits one capture globally, a two-second cooldown after each attempt and at most twenty requests per minute per paired session. Capture uses an independent lock so its binary transfer does not hold the input-control lock; the brief identity check still shares the existing control lock. ADB output is capped at 32 MiB, with a twenty-second capture deadline and process cleanup. A fixed command uses the TV's built-in `gzip -1` for raw screenshot pixels when available, with PNG fallback when gzip is absent. Gzip expansion is bounded to 40,000,016 bytes; decoded RGB, RGBA or RGBX buffers must have an exact supported 12- or 16-byte header and pixel length. Both capture paths reject images beyond 4096 pixels per side or ten million pixels. The Mac converts accepted images in memory to a JPEG no larger than 1280 × 720 and 1 MiB. Production frames are neither written to disk nor logged, and are not cached across viewers. The browser replaces and revokes its temporary image URLs; the existing trusted-LAN HTTP requirements still apply.

Errors include `preview_busy` and `preview_rate_limited` (429), plus `preview_unavailable`, `preview_dependency_missing`, `preview_invalid_image` and `preview_timeout` (503). Existing pairing and Fire TV availability errors also apply. A failed frame is never presented as a new capture.

## Native television AirPlay

**Tools → AirPlay** opens connection help, including before remote pairing or while the Fire TV connection is offline. Put your Apple device and compatible Samsung TV on the same Wi-Fi, then use **Control Center → Screen Mirroring** and choose the Samsung receiver. Enter the TV's code if requested. The guide sends no device command and does not enable a receiver on the Fire TV. Native AirPlay streams directly to Samsung, so the companion computer does not need to remain awake for casting. The remote webpage itself still requires its server to load.

## Device information, weather and wallpaper

**Device** reads Fire TV RAM and the internal `/data` partition on demand. The phone polls every ten seconds only while that sheet is open and visible; the server shares a ten-second cache across paired clients. Each sample verifies the fixed TV identity, takes the existing ADB lock without waiting behind controls, and uses only `cat /proc/meminfo` and `df -k /data`, with one-second command deadlines. It never reconnects a device automatically. RAM usage is total minus `MemAvailable`, which includes reclaimable cache; it is not a per-app reading. Storage describes the usable data partition, not the advertised flash capacity. Missing or unsupported readings remain unavailable rather than showing zero. An interrupted refresh keeps the last successful sample, its age and a stale flag.

**Weather** shows current conditions and seven daily forecasts. Fetches use the fixed HTTPS [Open-Meteo forecast API](https://open-meteo.com/en/docs), normal certificate verification, no redirects, a three-second socket timeout and a 64 KiB response limit. The cache refreshes at most once per fifteen minutes, with one-minute backoff after a failure; there is no background scheduler. Failed refreshes retain visibly dated/stale forecasts. The API also returns the current `localDate` in the configured timezone so an older cached day is never labelled Today just because it is first in the list. The browser contacts only this companion, and the companion sends the configured city coordinates to Open-Meteo. No precise phone geolocation is requested. Weather data requires the linked Open-Meteo attribution shown beside the forecast. The free API is for noncommercial use, including personal home automation; see [its terms](https://open-meteo.com/en/terms).

**ESPN** is available as an additional Open ESPN action in Settings, keeping the six-app dock compact. Its fixed shortcut targets the official installed `com.espn.gtv` application. Install ESPN through the Fire TV Appstore first; the remote does not download APKs or bypass provider authentication. Streaming requires your own ESPN or television-provider access for the selected content.

**Wallpaper** chooses Automatic, Morning, Dusk, Night, Annapurna or Pashupati for Nepal Home. The API sends only an explicit allowlisted broadcast to `com.gathin.tvhome/.WallpaperCommandReceiver`, protected by Android's `DUMP` permission, and requires the launcher's positive acknowledgement. The original modes need launcher v19+; Annapurna and Pashupati need v22. An older/missing launcher returns `wallpaper_unavailable`; no arbitrary image URL, filesystem path or system settings write is accepted. Applying changes the launcher's saved scene; a separate Home action shows the launcher if another app is in front.

| Request | Exact body | Meaning |
| --- | --- | --- |
| GET /api/device | none | Cached identity-checked RAM/data-partition sample, age and stale flag |
| GET /api/weather | none | Optional current conditions and seven-day forecast, age and provider attribution |
| POST /api/wallpaper | `{"mode":"dusk"}` | Apply one native Nepal Home scene after receiver acknowledgement |

All three routes require the existing paired browser session and Host/Origin checks. The two read routes share a thirty-request-per-minute session ceiling and do not run from `/api/status`. Wallpaper uses the existing bounded Fire TV control lock and rate limit. A transport failure never queues or retries the change.

## Offline validation

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s remote/tests -v
node --test remote/tests/test_frontend.js
```

Tests use synthetic private addresses, in-memory HTTP sockets and fake device clients. They do not bind a listener, discover devices or contact a TV. Coverage includes configuration/permissions, identity gates, token binding, authentication, Host/Origin checks, payload limits, path containment, command allowlists, concurrency/rates, timeout budgets and calendar boundaries.

## Additional wallpaper scenes

Launcher v22 / 4.7.0 adds **Annapurna** (a mountain lake at blue hour) and **Pashupati** (a Nepal-inspired Hindu temple at twilight). Choose either in TV Tools → Wallpaper, Appearance → Wallpaper, or the phone Wallpaper sheet and press Apply. Existing Automatic/Morning/Dusk/Night choices remain available; Automatic retains its San Mateo schedule. The two new scenes are fixed manual choices. New modes need v22 on the TV; an older receiver rejects them rather than reporting a successful apply. Only the selected scene is loaded into the launcher image cache.
