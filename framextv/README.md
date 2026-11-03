# Theater for Fire TV 2.5.1

Version 2.5.1 replaces the red monogram with a colorful film-camera icon on a rounded dark tile. The app icon, home header, poster fallback and TV banner share the new artwork; Nepal Home reads the updated icon from the installed app. This is an artwork update and preserves saved progress and subtitle preferences when installed over the existing app with the same signing key.

Version 2.5 opens on a native browse home with **Home**, **Movies**, **TV shows**, and **Search** tabs. A featured title and horizontal poster shelves show the provider’s current **Trending now**, **New releases**, **Popular movies**, and **Popular shows** feeds. Movies also offers **Action**, **Science fiction**, **Thrillers**, and **Comedy**. Continue watching appears first when saved progress exists. No account or new login is required.

Version 2.4 added **Continue watching**, per-movie and per-episode resume positions, and a saved **Subtitles** preference. Progress stays in private app storage on the Fire TV.

Version 2.3 automatically prefers the highest video resolution offered by the current source in the embedded player's Quality menu, using the displayed bitrate to break resolution ties. It verifies the selected option and closes settings it opened. This is a bounded, best-effort preference: the current source, device and network still determine available quality and reliable playback. It does not upscale video or guarantee 4K.

An independent TV app with native movie/show search, poster results, and season/episode pickers, using frameXTV as its provider. Selecting a title opens the provider's embedded player inside the app. Amazon Silk is not used. The app runs directly on Fire TV without the computer companion; phone remote controls still require that companion.

Version 2.5.1 (versionCode 9) includes title commands from the phone, immersive player controls, the film-camera icon, and a matching TV banner with Theater lettering. It retains the empty/loading-grid crash and zero-result fixes from 2.2.1. The app keeps the original `app.nepal.framextv/.MainActivity` component; an update signed with your existing key keeps the Home tile and remote shortcut. It does not change the launcher's appearance, app order or physical Home behavior.

## Use

Browse with Left/Right within a row and Up/Down between rows. Select a movie to watch, or a show to choose a season and episode. Returning from playback retains the browsing position. Back from Movies, TV shows, or Search returns to Home; Back on Home leaves Theater. The featured title does not auto-rotate or play a trailer.

Choose the **Search** tab to enter a title with the TV keyboard or the phone remote's Type on TV. Select **Search**, then choose a result. Movies open directly. TV shows present native season and episode lists first. All / Movies / TV shows filters narrow the returned results.

Choose a card in the **Continue watching** home row, or **Continue watching** beside the search filters, to reopen a saved title. Both this list and an already-watched search result offer **Resume** or **Start over**. The saved-title choices also offer **Remove**. Episodes keep separate positions. Saving begins after 15 seconds and samples about every five seconds; nearly finished titles (95% watched or at most 30 seconds remaining) leave the list. Up to 40 records are retained, with the 24 most recent shown. Earlier app versions did not record positions, so their previous sessions cannot be recovered. Uninstalling or clearing app data removes progress.

Resume waits for a seekable player and verifies the restored position before requesting playback. If the source cannot restore it, Theater preserves the bookmark and offers Retry or explicit Play from the beginning. A manual seek takes over from pending resume; Pause remains respected.

The player fills the window in immersive fullscreen. Native Back, Play, Pause, −10s, +10s, Subtitles and Retry controls fade after four seconds without interaction once the player document has loaded. D-pad, Menu or touch reveals them; Menu also switches focus between native controls and the provider player. Loading and error controls stay visible. Automatic hiding is disabled when accessibility touch exploration is enabled. **Back** leaves provider fullscreen first, then returns to search results. Leaving the app stops its player document.

**Subtitles** offers **On (English preferred)** or **Off**, saved across titles and app restarts. On is the default: it selects English when offered, otherwise the first available track. The source must supply subtitles; Theater does not generate or download missing tracks. Its automatic selection uses the provider's own controls and yields to a manual choice. Subtitle and quality selection coordinate so they do not open competing menus.

Playback uses the provider's web embed, not Android's native MediaPlayer decoder. Theater disables the provider's startup autoplay and uses a bounded readiness check to send its normal Play command when ready. An explicit Pause cancels that pending start. The provider controls available streams, subtitles, compatibility and content-access requirements; a provider error or manual Play step may still occur. Play/Pause/seek controls send its documented commands. Sending a command or loading the player document does not confirm playback.

The automatic quality preference uses the provider's existing settings controls; it sends no undocumented quality command and does not read stream URLs. Adaptive quality levels take precedence over alternate mirrors listed in the same menu. It leaves an already-open settings menu alone and yields to navigation during its selection or an explicit manual settings choice. Native Play/Pause while waiting does not prevent a later quality adjustment. Navigation, Retry and leaving the player cancel pending work. If the source offers no numeric quality choices, its default remains in effect. Automatic quality selection does not reveal or pin Theater's native controls, play a paused video, or change subtitles and playback speed. Provider UI changes may require an update.

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

Browse home uses one bounded `https://api.framextv.cc/api/home` request for its eight fixed category feeds. Saved titles can use `https://api.framextv.cc/api/details?id=<id>&type=movie|tv` to fill missing artwork. Category names reflect the provider’s feeds; New releases is not a guarantee that a particular title has a playable source.

These are observed website contracts, not a promised versioned API. Search currently shows up to 24 results from the first page. Browse shelves show up to 12 titles each. Posters and featured backdrops are restricted to HTTPS `image.tmdb.org`. No third-party API keys are copied or required.

Player URLs follow the [provider's embed documentation](https://framextv.cc/api): `https://framextv.xyz/embed/<id>` and `/embed/<id>/<season>/<episode>`. Only matching HTTPS embed routes on the provider's `.xyz` and `.cc` hosts can remain in the player. Backend or player changes can require an app update.

Networking runs off the UI thread with bounded payloads, timeouts, cancellation and a small poster cache. Home artwork has two workers, a 6 MiB cache and visible-only image bindings; offscreen rows release their displayed bitmaps. Search and home work are canceled separately, so switching tabs cancels an old selection without opening stale dialogs. Browsing failures leave Search and local Continue watching available, with a D-pad-accessible Retry action. Default TLS certificate/hostname validation is preserved. The embedded WebView has no native JavaScript bridge, file/content access, downloads, file chooser, external-app navigation or permission grants. Pop-up windows and mixed HTTP content are blocked. Backgrounding clears the player document.

Only Android's normal **Internet** permission is requested. Private local progress stores bounded title identifiers, episode numbers, titles, timing and update timestamps; the subtitle preference is also local. No search history, account handling, analytics, media extraction, decryption or DRM bypass is implemented. No progress is sent to the companion or a cloud account. The provider receives search and playback requests made to its service.

## Known issues

The 2.5 API-25 check verified the native Home, Movies and TV shows tabs, loaded artwork while moving across and between poster rows, and opened the season picker. The existing phone-request handoff correctly opened Search and handled no matches. A new Continue watching card displayed saved progress, resumed at 0:44, and continued advancing with English subtitles selected. The test entry was removed afterward. The build and lint checks pass; 21 catalog policy checks and eight local artwork lifecycle checks also passed.

The 2.4 API-25 check saved a movie position at 2:23, retained it after returning Home, and resumed at 2:23 before playback advanced. The provider confirmed English subtitle selection and Off; the saved Off preference survived reopening the player. Offline fixtures cover separate episode positions, failed/stale seeks, completion cleanup, subtitle availability and quality-menu coordination.

API-25 checks confirmed that phone title requests wake Fire TV and open the correct Inception and The Matrix player pages. No new native crashes were observed after the empty/loading-grid fix. Search and the provider's explicit `results:null` / `totalResults:0` response were also checked. The 2.3 quality check confirmed a selected 1080p source, preserved Pause during selection, closed its settings and hid native controls. A short explicit-Play check advanced for 11 seconds with a decoded 1920×800 frame.

Earlier movie checks stalled, and the short 2.3 check does not establish reliable long-form playback. The app remains a source preview; loading a player, seeing its duration or sending Play does not confirm continuous playback. Provider or WebView changes may require further fixes. Theater uses the documented web embed; it has no direct-media extraction or alternative playback backend.

## Build and install

Use JDK 17, Android SDK platform 34 and build-tools 34.0.0. AGP 8.5.2 and the included Gradle 8.9 wrapper are pinned in source. Minimum API is 25; target API is 29. No external app dependencies are required. Set `ANDROID_HOME` to your SDK directory, or create ignored `framextv/local.properties` with your own `sdk.dir`. Keep SDK paths, build caches, `ANDROID_USER_HOME` and signing material outside published source.

From the repository root:

```sh
cd framextv
./gradlew --no-daemon assembleDebug lintDebug
adb -s YOUR_FIRE_TV:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

From the repository root, `node --test framextv/tests/test_quality.js framextv/tests/test_playback.js` checks the packaged quality, resume and subtitle controllers against offline player fixtures. `sh framextv/tests/run_watch_progress.sh` uses the JDK to check the local progress policy, including separate episodes, completion, bounds and stale updates. `sh framextv/tests/run_catalog_policy.sh` checks the production browse/search/detail parser against synthetic responses; it needs `ANDROID_HOME` pointing to SDK platform 34 for compile-only JSON signatures. These checks need no TV or network connection.

The first build downloads Gradle and build dependencies. Add `--offline` only after those artifacts are cached. No compiled APK or private signing key is distributed here. Use the same private signing key to update an existing installation; a different debug key cannot replace it in place. Never publish signing or ADB keys. The normal Leanback entry appears with other installed apps in Nepal Home; the existing remote action `framextv` launches this same component.

The film-camera icon is generated project artwork, packaged at 512 × 512 pixels; the TV banner retains the original geometric Theater lettering. Source and project artwork follow the surrounding repository's MIT license. Provider content and branding are not relicensed or affiliated with this project.
