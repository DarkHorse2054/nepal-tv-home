# Local Fire TV remote

The UI/UX Pro Max redesign uses a warm charcoal Himalayan theme, a sculpted glass directional pad, a compact floating app dock, and clear Samsung / Fire TV control groups. App names appear on focus, hover or press; small screens reflow to named tiles. Typing, Sources, pairing and settings share matching sheets with visible in-sheet action feedback. See [the design system](DESIGN.md) for guidance, tokens and verification limits.

The companion server supports macOS/Linux with POSIX private-file checks; a phone or browser on any platform can use it on the same LAN. Keep the computer awake, the Fire TV powered, and ADB authorized. This repository does not include personal addresses, saved authorizations, or a configured TV.

The phone interface includes a compact glass directional pad, navigation/playback keys, six app shortcuts and sheets for typing, Samsung Sources and connection settings. It distinguishes an unavailable Mac, an offline Fire TV and an expired pairing session. Reconnect checks the configured target without replaying commands. On especially narrow screens, app shortcuts use two rows to preserve touch targets.

## Configure before starting

Requires Python 3.9+, an IANA timezone database, and Android Platform Tools. Put `adb` on PATH or supply its absolute path with `--adb`. Samsung support additionally needs `websockets>=15,<16` in the Python environment running the server.

From the repository root:

```sh
umask 077
mkdir -p remote/state
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

Open the printed LAN URL from your phone and enter the six-digit PIN shown in the terminal. Browser sessions last up to eight hours and are stored in session storage. Stopping the server invalidates them. This is an HTTP service for a trusted home LAN; a public static host cannot provide its local ADB bridge. Do not publish its listener through a public tunnel or router forwarding.

ADB uses your environment's normal authorization/key location. No SDK, private keys, or machine-specific runtime is bundled. If the Fire TV's DHCP address changes, update the private config and restart; the server does not scan for a replacement.

## Optional Samsung panel and Source controls

Samsung support uses the local Tizen TLS WebSocket endpoint on port 8002. Compatible models must report the configured exact model and token-auth support. In the webpage, choose Connect Samsung and accept Allow on your own television. This approval is separate from the webpage PIN.

Authorizations are saved as `remote/state/samsung-<target-fingerprint>.json` with mode `0600` in a `0700` directory. The filename and file contents bind the token to the configured host and model. Tokens are never returned to the webpage or logged. Keep all `state/` contents out of commits and release archives.

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

Every POST requires same-origin Origin, JSON and the paired webpage bearer token, except `/api/pair`, which takes the terminal PIN. No CORS access is granted.

| Request | Exact body | Meaning |
| --- | --- | --- |
| POST /api/pair | `{"pin":"123456"}` | Create an eight-hour browser session |
| GET /api/status | none | Fire TV availability, configured labels/dates, local Samsung authorization state |
| POST /api/key | `{"key":"home"}` | One allowlisted Fire TV key |
| POST /api/app | `{"app":"paramount"}` | Launch one fixed known app component |
| POST /api/text | `{"text":"Kathmandu"}` | Send bounded text once to the selected field |
| POST /api/reconnect | `{}` | Check the configured Fire TV; returns `{ok:true,online:boolean}` |
| POST /api/preview/frame | `{}` | One bounded JPEG screen capture; explicit Start in the phone sheet |
| POST /api/samsung/pair | `{}` | Explicit bounded Samsung approval request |
| POST /api/samsung/key | `{"key":"source"}` | One allowlisted Samsung panel/navigation/test click |

Fire TV keys: `up`, `down`, `left`, `right`, `ok`, `home`, `back`, `menu`, `play_pause`, `rewind`, `fast_forward`, `wake`, `sleep`, `enter`, `delete`. App aliases: `netflix`, `youtube`, `disney`, `paramount`, `hulu`, `npl`, `espn`. The NPL alias targets the independent `app.nepal.npltv` app, which must be installed separately and requires the user's own provider account/subscription. Components can differ across firmware or app versions; a launch failure does not install an app or retry an arbitrary component.

Text errors include `invalid_text_length`, `unsupported_text_character`, `split_text_required` and `text_send_uncertain`. The last means some or all text may have arrived, so check the TV before retrying. Queued inputs expire after a short deadline and are invalidated on blur, hiding, disconnect or sheet transitions; releasing a held key cancels its queued repeats. Reconnect never replays previous controls.

A manifest and original local icon support browser bookmarking. The plain-HTTP LAN service has no service worker or offline-control support. Browser sessions expire after eight hours or a server restart; a manifest does not change that behavior.

Samsung keys: `source`, `up`, `down`, `left`, `right`, `ok`, `back`, `volume_up`, `volume_down`, `mute`, `power_off`, `hdmi1`, `hdmi2`, `hdmi3`, `hdmi4`. Raw `KEY_*` strings, arbitrary command bodies and generic `power` are rejected. Pairing can return authorized or pending; pending is not approval. Sends return `state:"sent"`, `key`, `sentAt`, `experimental` and `inputVerified:false`; HDMI tests have `experimental:true`. Status adds `sourceLabels` and `lastSent`, while `directHdmi` and `sourceReadback` remain false. The backend permits at most eight Fire TV controls or four Samsung controls per second, using separate locks. This is a request ceiling, not a recommended hardware cadence. Run observed HDMI tests one at a time, leaving several seconds to see the result. Failed inputs are not automatically retried.

Home sends native Android key code 3. This remote does not change the default launcher, create a redirector, or root a device. `ok:true` means the transport accepted a command, not that the TV's visible result or DRM playback was independently verified.

Date status includes `localDate`, `localTime`, `localLabel`, `secondaryDate`, `secondaryTime` and `secondaryLabel`. The historical `sanMateo`, `nepal` and `kathmanduTime` fields remain aliases for existing frontend compatibility; they use your configured zones. The bundled BS calendar ends at BS 2083 and falls back to Gregorian beyond its table. Its license is retained in `data/NEPALI-CALENDAR-LICENSE.txt`.

## Screen preview

Choose **Screen preview** below the Tools row, then **Start preview**. Opening the sheet does not capture anything. It shows refreshed screenshots of the Fire TV, with a D-pad, Back and Home inside the sheet. Requests run one at a time; the next starts three seconds after the previous request finishes, so capture time adds to that interval. The displayed time marks the start of the capture attempt, alongside its transfer latency. This preview refreshes still images and has no audio.

**Stop preview** pauses updates and retains the last image, labeled as paused. Closing the sheet, opening another sheet, hiding/leaving the page, losing the connection or ending the session stops requests and clears the image. Starting again is explicit. A request already accepted by the server may finish within its bounded deadline after the browser cancels; there is no continuing recorder or server-side capture loop.

Preview requires the companion computer to stay awake, Fire TV to be reachable, and its existing ADB authorization to remain valid. It captures the **Fire TV stick's output only**: Samsung apps, Samsung's native AirPlay receiver and other HDMI sources are outside it. Protected video or secure windows may be blank. Images can include visible messages, account details or other screen content; only start preview when you intend to view that screen.

Preview additionally needs [Pillow](https://pillow.readthedocs.io/en/stable/installation/basic-installation.html) in the Python environment that runs the companion:

```sh
python3 -m pip install --upgrade Pillow
```

Use that environment's Python executable if it differs from `python3`. Pillow is optional: without it, pairing, remote controls and the other tools still work, while preview reports `preview_dependency_missing`.

Each frame is an authenticated same-origin `POST /api/preview/frame` with the exact JSON body `{}` and the existing bearer session header. GET and HEAD never capture. The response is `image/jpeg` with `Cache-Control: no-store`, `X-Preview-Captured-At` in UTC and `X-Preview-Capture-Ms`. Session tokens stay in headers, never image URLs. No capture is triggered by ordinary status polling.

The backend checks the configured TV identity for every capture, without reconnecting or waking it. It permits one capture globally, a two-second cooldown after each attempt and at most twenty requests per minute per paired session. Capture uses an independent lock so its binary transfer does not hold the input-control lock; the brief identity check still shares the existing control lock. ADB output is capped at 32 MiB, with a twenty-second capture deadline and process cleanup. A fixed command uses the TV's built-in `gzip -1` for raw screenshot pixels when available, with PNG fallback when gzip is absent. Gzip expansion is bounded to 40,000,016 bytes; decoded RGB, RGBA or RGBX buffers must have an exact supported 12- or 16-byte header and pixel length. Both capture paths reject images beyond 4096 pixels per side or ten million pixels. The Mac converts accepted images in memory to a JPEG no larger than 1280 × 720 and 1 MiB. Production frames are neither written to disk nor logged, and are not cached across viewers. The browser replaces and revokes its temporary image URLs; the existing trusted-LAN HTTP requirements still apply.

Errors include `preview_busy` and `preview_rate_limited` (429), plus `preview_unavailable`, `preview_dependency_missing`, `preview_invalid_image` and `preview_timeout` (503). Existing pairing and Fire TV availability errors also apply. A failed frame is never presented as a new capture.

## Native television AirPlay

**Tools → AirPlay** opens connection help, including before remote pairing or while the Fire TV connection is offline. Put your Apple device and compatible Samsung TV on the same Wi-Fi, then use **Control Center → Screen Mirroring** and choose the Samsung receiver. Enter the TV's code if requested. The guide sends no device command and does not enable a receiver on the Fire TV. Native AirPlay streams directly to Samsung, so the companion computer does not need to remain awake for casting. The remote webpage itself still requires its server to load.

## Device information, weather and wallpaper

**Device** reads Fire TV RAM and the internal `/data` partition on demand. The phone polls every ten seconds only while that sheet is open and visible; the server shares a ten-second cache across paired clients. Each sample verifies the fixed TV identity, takes the existing ADB lock without waiting behind controls, and uses only `cat /proc/meminfo` and `df -k /data`, with one-second command deadlines. It never reconnects a device automatically. RAM usage is total minus `MemAvailable`, which includes reclaimable cache; it is not a per-app reading. Storage describes the usable data partition, not the advertised flash capacity. Missing or unsupported readings remain unavailable rather than showing zero. An interrupted refresh keeps the last successful sample, its age and a stale flag.

**Weather** shows current conditions and seven daily forecasts. Fetches use the fixed HTTPS [Open-Meteo forecast API](https://open-meteo.com/en/docs), normal certificate verification, no redirects, a three-second socket timeout and a 64 KiB response limit. The cache refreshes at most once per fifteen minutes, with one-minute backoff after a failure; there is no background scheduler. Failed refreshes retain visibly dated/stale forecasts. The API also returns the current `localDate` in the configured timezone so an older cached day is never labelled Today just because it is first in the list. The browser contacts only this companion, and the companion sends the configured city coordinates to Open-Meteo. No precise phone geolocation is requested. Weather data requires the linked Open-Meteo attribution shown beside the forecast. The free API is for noncommercial use, including personal home automation; see [its terms](https://open-meteo.com/en/terms).

**ESPN** is available as an additional Open ESPN action in Settings, keeping the six-app dock compact. Its fixed shortcut targets the official installed `com.espn.gtv` application. Install ESPN through the Fire TV Appstore first; the remote does not download APKs or bypass provider authentication. Streaming requires your own ESPN or television-provider access for the selected content.

**Wallpaper** chooses Automatic, Morning, Dusk or Night for Nepal Home. The API sends only an explicit allowlisted broadcast to `com.gathin.tvhome/.WallpaperCommandReceiver`, protected by Android's `DUMP` permission, and requires the launcher's positive acknowledgement. This needs launcher version 19 or later. An older/missing launcher returns `wallpaper_unavailable`; no arbitrary image URL, filesystem path or system settings write is accepted. Applying changes the launcher's saved scene; a separate Home action shows the launcher if another app is in front.

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
