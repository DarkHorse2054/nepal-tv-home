# Samsung panel controls and observed HDMI testing

The companion includes volume up/down, mute, explicit power off and Samsung Source-menu navigation. Fixed discrete HDMI keys remain available only through the helper/API for observed testing. They are not established as working on the recorded TU7000 firmware. Opening Samsung’s native Source menu was previously confirmed; a successful transport write alone does not verify other panel behavior.

## What is implemented

- Fixed action aliases only: `hdmi1`-`hdmi4` map to `KEY_HDMI1`-`KEY_HDMI4`; `volume_up`, `volume_down`, `mute` and `power_off` map to `KEY_VOLUP`, `KEY_VOLDOWN`, `KEY_MUTE` and `KEY_POWEROFF`.
- Existing source/navigation, explicit on-TV pairing, exact-model gate, fixed target ports, private token file, webpage bearer/Origin checks, timeouts and independent Samsung/Fire TV locks remain in place.
- Samsung controls sit above Fire TV navigation. Sources opens a navigation sheet; experimental HDMI keys are deliberately absent from the normal UI. Samsung keyboard navigation can work while Fire TV is offline.
- `Last sent` records only a successful WebSocket write, with its timestamp, in server memory. Failed sends do not overwrite the previous entry. It does not mean the television selected or accepted an input.
- Optional private config labels, such as `"source_labels":{"hdmi1":"Fire TV","hdmi2":"Console"}`. Labels are displayed with textContent and cannot change key codes, device identity or authorization filenames.

## Route A: test with someone watching the panel

1. Turn the Samsung on using its physical remote. Keep that remote nearby and identify which actual input returns to Fire TV. Use only physical inputs present on this TV; HDMI 3/4 actions are generic protocol tests, not a claim that this model has four sockets.
2. Start the configured companion, open its webpage, pair the webpage and connect Samsung if needed. For an experimental HDMI trial, use the fixed command-line helper shown below; normal Sources navigation stays in the webpage.
3. With the physical remote, select a *different known input* from the target. Send just the target helper command once, using only an actual port on your TV. Wait several seconds and watch the panel.
4. Record the starting input, target, timestamp and visible result as switched / ignored / uncertain / not applicable. The webpage's Last sent line is transport evidence only. An empty input may show its input name or No Signal; lack of a picture alone is not proof the command failed.
5. Return to the original Fire TV input with the physical remote. Repeat from each other actual source, three times per direction. For two inputs this is six transitions; include TV or other sources only if actually available and relevant.
Use the configured companion’s Python environment and your private configuration. For example, to test HDMI 1 once:

```sh
python3 -B remote/samsung.py --config remote/state/config.json nav hdmi1
```

Repeat the procedure with `hdmi2` only if applicable. The helper accepts fixed aliases, not arbitrary key codes.

6. Test volume down once, volume up once and mute/unmute while observing the Samsung OSD/audio. Test Power off last, because this version has no power-on or Wake-on-LAN action. Record actual outcomes separately from HDMI behavior.

| Trial | Starting input | Target | Last sent time | Observed result | Notes / return path |
| --- | --- | --- | --- | --- | --- |
| Pending | Not observed | Not observed | Not sent to real TV | UNTESTED | Physical remote retained |

Keep a local test log without publishing private addresses, pairing PINs or authorization tokens. Do not mark a source working because the command returned HTTP 200. Promote one-tap source buttons only after observed transitions pass consistently, using the real cable names in config.

## If discrete keys do not work

Calibrate the actual Samsung Source menu before considering Route B. Record its order, initial focus, wrapping behavior, connected/disconnected input visibility and any dynamic tiles. Repeated Left presses are **not assumed to clamp**: if the menu wraps or its layout changes, that proposed macro is not deterministic. Evaluate delays on this firmware; no universal 1.5-second interval is established by this project. Test every real start/target combination three times and retain manual Source navigation as the observable return path.

Route C (UPnP) remains investigation only. This project has not established supported services, source switching or input readback on this exact Samsung. No discovery scan or UPnP integration has been added.

## Boundaries and next work

This stage does not alter the Fire TV launcher, native Home selection, installed apps, privacy settings, firmware, Samsung pairing approval or trust settings. Native Android Sources is a separate launcher feature. The webpage includes bounded text entry and connection recovery; see [remote setup and behavior](README.md). Wake-on-LAN, Source-menu macros and a scheduled health watchdog are not implemented. Device RAM/storage readings and weather are available separately through the remote’s Tools panels.

The Python companion now pins the self-signed leaf certificate during explicit, approved first pairing on a trusted LAN. Both HTTPS identity and WebSocket connections verify that exact leaf before sending application requests or a saved token. Certificate changes are rejected even during re-pairing. Legacy unpinned tokens require explicit pairing; pins are not silently replaced. Offline tests verify mismatch refusal before GET/Upgrade, redirect refusal and private token storage. First use still depends on the trusted LAN and the owner's TV approval. Browser-to-companion HTTP is a separate unencrypted transport; see [security scope](../SECURITY.md).

Protocol references checked while preparing this stage:

- [SamsungTVWS remote implementation](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/samsungtvws/remote.py): the fixed SendRemoteKey click shape and volume/mute keys.
- [Samsung TV Control key enum](https://github.com/Toxblh/samsung-tv-control/blob/master/src/keys.ts): lists the discrete HDMI and explicit power-off key names; listing a key is not a compatibility test.
- [Samsung TU7000 specification](https://image-us.samsung.com/SamsungUS/samsung/us/tvs/TU7000_TV_Spec_Sheet_052621.pdf): lists two HDMI ports for this product family. Generic HDMI 3/4 key tests do not create extra physical sockets.
- [Homebridge Samsung key-support report](https://github.com/tavicu/homebridge-samsung-tizen/issues/167) and [Home Assistant source-selection report](https://github.com/home-assistant/core/issues/147250): reports illustrate why model/firmware behavior must be observed, not inferred from a key name. These are reports about other setups, not proof about this owner's TV.
