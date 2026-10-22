# Local Fire TV remote

The companion server supports macOS/Linux with POSIX private-file checks; a phone or browser on any platform can use it on the same LAN. Keep the computer awake, the Fire TV powered, and ADB authorized. This repository does not include personal addresses, saved authorizations, or a configured TV.

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

For example, an optional Samsung target has this shape, using your own values:

```json
{"host":"192.168.1.101","port":8002,"model":"YOUR_VERIFIED_SAMSUNG_MODEL","source_labels":{"hdmi1":"Fire TV","hdmi2":"Console"}}
```

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

The Samsung panel sits above Fire TV controls and remains usable when Fire TV is offline. Volume up/down, mute and explicit Power off are fixed clicks; no power toggle, power-on, Wake-on-LAN or automatic retry is included. A sleeping panel may be unreachable, so use its physical remote to wake it for testing.

Sources opens a foldout with Open Sources and Up/Down/Left/Right/OK/Back navigation. A second foldout contains **experimental** discrete HDMI 1-4 key tests. These keys are not established as working on the recorded TU7000 firmware, and 3/4 may not correspond to physical sockets. Watch the TV after each test. No Source-menu macro, current-input readback or Samsung screenshot API is implemented. See [the observed-test procedure](SAMSUNG-CONTROLS.md).

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

Every POST requires same-origin Origin, JSON and the paired webpage bearer token, except `/api/pair`, which takes the terminal PIN. No CORS access is granted.

| Request | Exact body | Meaning |
| --- | --- | --- |
| POST /api/pair | `{"pin":"123456"}` | Create an eight-hour browser session |
| GET /api/status | none | Fire TV availability, configured labels/dates, local Samsung authorization state |
| POST /api/key | `{"key":"home"}` | One allowlisted Fire TV key |
| POST /api/app | `{"app":"paramount"}` | Launch one fixed known app component |
| POST /api/samsung/pair | `{}` | Explicit bounded Samsung approval request |
| POST /api/samsung/key | `{"key":"source"}` | One allowlisted Samsung panel/navigation/test click |

Fire TV keys: `up`, `down`, `left`, `right`, `ok`, `home`, `back`, `menu`, `play_pause`, `rewind`, `fast_forward`, `wake`, `sleep`. App aliases remain the fixed initial set: Netflix, YouTube, Disney+, Paramount+, Hulu. Components can differ across firmware or app versions; a launch failure does not install an app or retry an arbitrary component.

Samsung keys: `source`, `up`, `down`, `left`, `right`, `ok`, `back`, `volume_up`, `volume_down`, `mute`, `power_off`, `hdmi1`, `hdmi2`, `hdmi3`, `hdmi4`. Raw `KEY_*` strings, arbitrary command bodies and generic `power` are rejected. Pairing can return authorized or pending; pending is not approval. Sends return `state:"sent"`, `key`, `sentAt`, `experimental` and `inputVerified:false`; HDMI tests have `experimental:true`. Status adds `sourceLabels` and `lastSent`, while `directHdmi` and `sourceReadback` remain false. The backend permits at most eight Fire TV controls or four Samsung controls per second, using separate locks. This is a request ceiling, not a recommended hardware cadence. Run observed HDMI tests one at a time, leaving several seconds to see the result. Failed inputs are not automatically retried.

Home sends native Android key code 3. This remote does not change the default launcher, create a redirector, or root a device. `ok:true` means the transport accepted a command, not that the TV's visible result or DRM playback was independently verified.

Date status includes `localDate`, `localTime`, `localLabel`, `secondaryDate`, `secondaryTime` and `secondaryLabel`. The historical `sanMateo`, `nepal` and `kathmanduTime` fields remain aliases for existing frontend compatibility; they use your configured zones. The bundled BS calendar ends at BS 2083 and falls back to Gregorian beyond its table. Its license is retained in `data/NEPALI-CALENDAR-LICENSE.txt`.

## Offline validation

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s remote/tests -v
```

Tests use synthetic private addresses, in-memory HTTP sockets and fake device clients. They do not bind a listener, discover devices or contact a TV. Coverage includes configuration/permissions, identity gates, token binding, authentication, Host/Origin checks, payload limits, path containment, command allowlists, concurrency/rates, timeout budgets and calendar boundaries.
