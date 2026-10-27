# Theater for Fire TV 2.2.1

Version 2.2.1 fixes a crash when a phone request reopens Theater while its results grid is empty or still loading. Returning from Home safely restores selection for the current results. The provider's explicit zero-result response now shows “No matches” rather than a search error.

An independent TV app with native movie/show search, poster results, and season/episode pickers, using frameXTV as its provider. Selecting a title opens the provider's embedded player inside the app. Amazon Silk is not used. The app runs directly on Fire TV without the computer companion; phone remote controls still require that companion.

Version 2.2.1 (versionCode 5) includes title commands from the phone, immersive player controls, the original red T icon with Theater lettering on a rounded black tile, and a matching TV banner. The app keeps the original `app.nepal.framextv/.MainActivity` component; an update signed with your existing key keeps the Home tile and remote shortcut. It does not change the launcher's appearance, app order or physical Home behavior.

## Use

Enter a title with the TV keyboard or the phone remote's Type on TV. Select **Search**, then choose a result. Movies open directly. TV shows present native season and episode lists first. All / Movies / TV shows filters narrow the returned results.

The player fills the window in immersive fullscreen. Native Back, Play, Pause, −10s, +10s and Retry controls fade after four seconds without interaction once the player document has loaded. D-pad, Menu or touch reveals them; Menu also switches focus between native controls and the provider player. Loading and error controls stay visible. Automatic hiding is disabled when accessibility touch exploration is enabled. **Back** leaves provider fullscreen first, then returns to search results. Leaving the app stops its player document.

Playback uses the provider's web embed, not Android's native MediaPlayer decoder. Theater disables the provider's startup autoplay and uses a bounded readiness check to send its normal Play command when ready. An explicit Pause cancels that pending start. The provider controls available streams, subtitles, compatibility and content-access requirements; a provider error or manual Play step may still occur. Play/Pause/seek controls send its documented commands. Sending a command or loading the player document does not confirm playback.

## Title requests from the phone

Open **Play on Theater** beside Live view in the phone remote. Enter a request or use your phone keyboard's microphone, then press **Search & play**. Examples:

- `play Inception (2010)`
- `play Breaking Bad s2e3` or `play Breaking Bad season 2 episode 3`
- `search for Dune`

The TV app parses these title commands locally and searches the provider catalog. An explicit Play/Watch request opens a single exact title match, also checking a supplied parenthesized year. Ambiguous matches stay on TV for you to choose. A bare title or Search/Find request shows results without autoplaying. Requested seasons and episodes are checked against the catalog before opening their player. The companion sends Fire TV's Wake key before this handoff so its clock screensaver does not cover Theater; it does not power on the display panel.

This is a small command parser. Recommendation AI is not configured; no cloud model, AI API or model key is used. The phone and companion do not keep request history or log request bodies. The provider still receives catalog and playback requests.

### Fixed Android request handoff

Integrators can explicitly start `app.nepal.framextv/.MainActivity` with action `app.nepal.framextv.action.REQUEST` and string extra `theater_request`. Encode the UTF-8 request as canonical, unpadded URL-safe Base64. The decoded request is limited to 160 Unicode codepoints; control characters, malformed text and oversized inputs are rejected. The extra is consumed once. It represents a title command, never an arbitrary URI, shell command or player URL.

## Provider integration

The provider's public frontend was inspected and these keyless first-party catalog requests were verified on the tested API-25 Fire TV:

- `https://api.framextv.cc/api/search?q=<encoded-title>&type=all&page=1`
- `https://api.framextv.cc/api/details?id=<tv-id>&type=tv`
- `https://api.framextv.cc/api/season?id=<tv-id>&season=<number>`

These are observed website contracts, not a promised versioned API. Search currently shows up to 24 results from the first page. Posters are restricted to HTTPS `image.tmdb.org`. No third-party API keys are copied or required.

Player URLs follow the [provider's embed documentation](https://framextv.cc/api): `https://framextv.xyz/embed/<id>` and `/embed/<id>/<season>/<episode>`. Only matching HTTPS embed routes on the provider's `.xyz` and `.cc` hosts can remain in the player. Backend or player changes can require an app update.

Networking runs off the UI thread with bounded payloads, timeouts, cancellation and a small poster cache. Default TLS certificate/hostname validation is preserved. The embedded WebView has no native JavaScript bridge, file/content access, downloads, file chooser, external-app navigation or permission grants. Pop-up windows and mixed HTTP content are blocked. Backgrounding clears the player document.

Only Android's normal **Internet** permission is requested. No account handling, persistent search/watch history, analytics, media extraction, decryption or DRM bypass is implemented. The provider receives search and playback requests made to its service.

## Known issues

The latest API-25 checks confirmed that phone title requests wake Fire TV and open the correct Inception and The Matrix player pages. No new native crashes were observed after the empty/loading-grid fix. Search and the provider's explicit `results:null` / `totalResults:0` response were also checked.

Continuous video stalled in those recent movie checks. Reliable provider playback remains unresolved; the app is a source preview, and loading a player, seeing its duration or sending Play does not confirm continuous playback. Provider or WebView changes may require further fixes. Theater uses the documented web embed; it has no direct-media extraction or alternative playback backend.

## Build and install

Use JDK 17, Android SDK platform 34 and build-tools 34.0.0. AGP 8.5.2 and the included Gradle 8.9 wrapper are pinned in source. Minimum API is 25; target API is 29. No external app dependencies are required. Set `ANDROID_HOME` to your SDK directory, or create ignored `framextv/local.properties` with your own `sdk.dir`. Keep SDK paths, build caches, `ANDROID_USER_HOME` and signing material outside published source.

From the repository root:

```sh
cd framextv
./gradlew --no-daemon assembleDebug lintDebug
adb -s YOUR_FIRE_TV:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle and build dependencies. Add `--offline` only after those artifacts are cached. No compiled APK or private signing key is distributed here. Use the same private signing key to update an existing installation; a different debug key cannot replace it in place. Never publish signing or ADB keys. The normal Leanback entry appears with other installed apps in Nepal Home; the existing remote action `framextv` launches this same component.

Source and original Theater artwork follow the surrounding repository's MIT license. Provider content and branding are not relicensed or affiliated with this project.
