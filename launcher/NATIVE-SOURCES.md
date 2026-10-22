# Sources on the TV home screen

Version 18 / 4.4.2-compact-brand-icons includes a display-and-input-arrow Sources icon immediately left of Wi-Fi and Settings. It connects directly from the Fire TV stick to the Samsung display; no running Mac or browser remote is required.

The owner confirmed that native Sources works in the v13 build. Version 14 is now installed and checked on the device: it removes the intermediate panel for paired TVs and reduces the navigation hint to one line.

## Pair once

1. Long-press Sources on Nepal Home to open **Inputs** management. An unpaired TV also needs setup before opening inputs.
2. Choose **TV connection**.
3. Enter the Samsung display's private IPv4 address and exact model code, separate from the Fire TV stick's values. The owner's installed copy is configured privately; shared source has no personal target.
4. Choose **Pair TV**, then select Allow for **Nepal Home** on the Samsung prompt. Use the Samsung remote for this initial approval if needed.
5. Choose **Open inputs**, or return Home and select Sources.

## Choose an input with the physical remote

For a paired TV, selecting Sources immediately requests Samsung's input picker. Use the Fire TV remote's arrows to choose an input and OK to select. Back sends Samsung Return and exits; Menu sends Return and opens Inputs management. Long-press Sources from Home also opens management. There is no fixed source-order macro or unverified HDMI 1/2 button: choose from Samsung's own input names and pictures.

Version 14 uses a 328 dp single-line pill near the top: **Arrows · OK · Back**, without dimming the picture. A successful socket write does not prove a source became active. The app does not claim a current-input name, capture Samsung overlays or read back the active HDMI port.

If you choose another HDMI input, the Fire TV image is no longer visible. Keep the Samsung remote available to return to the Fire TV input during initial testing. After returning, press Home to dismiss any old forwarding controls before reopening Sources.

## Connection and recovery

The Samsung must be powered on, reachable on the same LAN and support token-authorized TLS WebSocket remote control on port 8002. Configure an explicit RFC1918 IPv4 address; there is no network scan, cloud sign-in or arbitrary endpoint/key field. The exact model is checked before authorizing a connection or sending controls.

Pairing stores the approval token and certificate SHA-256 together in private Android preferences. Initial trust is allowed only after pressing Pair TV. The HTTPS identity lookup and WebSocket must present the same certificate. A changed stored certificate is rejected, including during re-pairing. Verify the address/device, then use **TV connection > Forget pairing** if a legitimate certificate change requires new approval. Changing the configured TV clears its previous credentials.

Normal reconnects require saved pairing and never silently initiate approval. Messages, queues, connection timeouts and key pacing are bounded. No global TLS validation is disabled. The first pairing still relies on trusting the selected local device and recognizing its approval prompt.

On the tested Samsung firmware, reusing the HTTPS identity connection for the WebSocket upgrade caused an EOF when the TV closed that connection. The identity request now explicitly uses `Connection: close`, so the WebSocket opens a fresh TLS connection. The same scoped certificate checks and stored pin still apply to both connections.

Native Home component selection, package cleanup, the custom clock screensaver and existing appearance/app preferences are separate from this feature. Updating this APK does not itself change the system Home resolver, root a device or disable packages.

## Recorded verification boundary

The owner's v11 update was installed in place using the same debug signing certificate as v10. Existing preferences were backed up. Android HOME still resolved to `com.gathin.tvhome/.MainActivity`; the new Sources icon was confirmed in the actual UI hierarchy and saved screenshot. Seven offline certificate/input-validation tests passed; assembly and lint completed with zero lint errors and 48 warnings.

Version 13 / 4.3.2-native-sources was installed on the actual Fire TV stick. Hardware logs confirm HTTPS identity, exact-model and certificate checks, followed by `WEBSOCKET_OPEN`, `AUTHORIZATION` and, after the owner selected Allow, `AUTH_READY`. Private preferences contained the saved token and certificate pin; their values were not printed. The request and native forwarding overlay were captured, and the owner then confirmed that native Sources works. This confirmation is specific to the tested Fire TV/Samsung pair. An ADB screenshot captures the Fire TV picture, not Samsung's separate overlay, and there is no programmatic active-input readback.

Version 14 / 4.3.3-native-sources is installed in place with the same signing identity and Home resolver. The private Samsung preference file was unchanged by the update. A single Sources click reconnected using the saved pairing, reached `AUTH_READY` and displayed the compact navigation hint without an intermediate menu. Menu returned to Inputs management, long-press opened management, and Back exited to Home. Actual UI screenshots were refreshed. Assembly and seven security tests passed; lint reported zero errors and 45 warnings. The transport and input-key mapping are unchanged from the owner-confirmed v13 path.

Version 15 changes the three toolbar vector icons only: Sources uses a display with an incoming arrow, Wi-Fi uses rounded arcs, and Settings uses adjustment sliders. The icons share a rounded 1.7-unit stroke and retain the existing contrast tint for focus. The APK assembled and passed lint; existing pairing and the custom Home resolver were preserved during the in-place update.

Version 16 retains this input-control path and toolbar icon family while refining the app dock. The installed update preserved the private Samsung, app-order and appearance preference files exactly. See [GLASS-DOCK.md](GLASS-DOCK.md) for dock-specific changes and verification.

Version 18 changes the Netflix and YouTube dock artwork to their compact official marks. Native input-control behavior is unchanged. See [BRAND-ICONS.md](BRAND-ICONS.md) for artwork provenance.
