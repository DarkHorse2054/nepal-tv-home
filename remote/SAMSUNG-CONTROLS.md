# Samsung panel controls and observed HDMI testing

This change prepares Route A from the independent feedback: fixed discrete HDMI keys through the existing authorized local Samsung connection. It also adds volume up/down, mute and explicit power off. All new hardware behavior is **untested on the owner's TV** until observed. The previously confirmed result remains opening Samsung's native Source menu.

## What is implemented

- Fixed action aliases only: `hdmi1`-`hdmi4` map to `KEY_HDMI1`-`KEY_HDMI4`; `volume_up`, `volume_down`, `mute` and `power_off` map to `KEY_VOLUP`, `KEY_VOLDOWN`, `KEY_MUTE` and `KEY_POWEROFF`.
- Existing source/navigation, explicit on-TV pairing, exact-model gate, fixed target ports, private token file, webpage bearer/Origin checks, timeouts and independent Samsung/Fire TV locks remain in place.
- Samsung controls above Fire TV controls; source navigation and experimental HDMI tests are folded away by default. Samsung keyboard navigation can work while Fire TV is offline.
- `Last sent` records only a successful WebSocket write, with its timestamp, in server memory. Failed sends do not overwrite the previous entry. It does not mean the television selected or accepted an input.
- Optional private config labels, such as `"source_labels":{"hdmi1":"Fire TV","hdmi2":"Console"}`. Labels are displayed with textContent and cannot change key codes, device identity or authorization filenames.

## Route A: test with someone watching the panel

1. Turn the Samsung on using its physical remote. Keep that remote nearby and identify which actual input returns to Fire TV. Use only physical inputs present on this TV; HDMI 3/4 buttons are generic protocol tests, not a claim that this model has four sockets.
2. Start the configured companion, open its webpage, pair the webpage and connect Samsung if needed. Go to Sources > Experimental HDMI tests.
3. With the physical remote, select a *different known input* from the target. Click just the target test button once. Wait several seconds and watch the panel.
4. Record the starting input, target, timestamp and visible result as switched / ignored / uncertain / not applicable. The webpage's Last sent line is transport evidence only. An empty input may show its input name or No Signal; lack of a picture alone is not proof the command failed.
5. Return to the original Fire TV input with the physical remote. Repeat from each other actual source, three times per direction. For two inputs this is six transitions; include TV or other sources only if actually available and relevant.
6. Test volume down once, volume up once and mute/unmute while observing the Samsung OSD/audio. Test Power off last, because this version has no power-on or Wake-on-LAN action. Record actual outcomes separately from HDMI behavior.

| Trial | Starting input | Target | Last sent time | Observed result | Notes / return path |
| --- | --- | --- | --- | --- | --- |
| Pending | Not observed | Not observed | Not sent to real TV | UNTESTED | Physical remote retained |

Keep a local test log without publishing private addresses, pairing PINs or authorization tokens. Do not mark a source working because the command returned HTTP 200. Promote one-tap source buttons only after observed transitions pass consistently, using the real cable names in config.

## If discrete keys do not work

Calibrate the actual Samsung Source menu before considering Route B. Record its order, initial focus, wrapping behavior, connected/disconnected input visibility and any dynamic tiles. Repeated Left presses are **not assumed to clamp**: if the menu wraps or its layout changes, that proposed macro is not deterministic. Evaluate delays on this firmware; no universal 1.5-second interval is established by this project. Test every real start/target combination three times and retain manual Source navigation as the observable return path.

Route C (UPnP) remains investigation only. This project has not established supported services, source switching or input readback on this exact Samsung. No discovery scan or UPnP integration has been added.

## Boundaries and next work

This stage does not alter the Fire TV launcher, native Home selection, installed apps, privacy settings, firmware, Samsung pairing approval or trust settings. Native Android Sources, Wake-on-LAN, Source-menu macros, monitoring, text entry and other suggested features are not implemented here.

The existing Python companion uses a connection-local context for Samsung's self-signed TLS service, without certificate pinning. Host/model checks and private tokens do not substitute for certificate authentication. This change does not claim to fix that pre-existing limitation. A future native or companion pinning implementation needs an explicit first-pair trust model, mismatch behavior and token-migration tests; it must not globally disable Android certificate validation.

Protocol references checked while preparing this stage:

- [SamsungTVWS remote implementation](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/remote.py): the fixed SendRemoteKey click shape and volume/mute keys.
- [Samsung TV Control key enum](https://github.com/Toxblh/samsung-tv-control/blob/master/src/keys.ts): lists the discrete HDMI and explicit power-off key names; listing a key is not a compatibility test.
- [Samsung TU7000 specification](https://image-us.samsung.com/SamsungUS/samsung/us/tvs/TU7000_TV_Spec_Sheet_052621.pdf): lists two HDMI ports for this product family. Generic HDMI 3/4 key tests do not create extra physical sockets.
- [Homebridge Samsung key-support report](https://github.com/tavicu/homebridge-samsung-tizen/issues/167) and [Home Assistant source-selection report](https://github.com/home-assistant/core/issues/147250): reports illustrate why model/firmware behavior must be observed, not inferred from a key name. These are reports about other setups, not proof about this owner's TV.
