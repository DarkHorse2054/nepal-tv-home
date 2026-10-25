# Third-party notices

## Base launcher

This project is adapted from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Its original MIT copyright and license notice are retained in [LICENSE](LICENSE). The Nepal-themed artwork, clock/date presentation, dock and local remote are adaptations in this source tree. Upstream authors do not endorse this fork or its device compatibility.

## Fonts

| Font | Included files | Notice |
| --- | --- | --- |
| Outfit, copyright 2021 The Outfit Project Authors | `launcher/app/src/main/res/font/outfit.ttf`, `outfit_modern.ttf`, and `remote/dist/assets/outfit.ttf` | [SIL Open Font License 1.1](launcher/FONT-LICENSE-OFL.txt); [upstream](https://github.com/Outfitio/Outfit-Fonts) |
| Noto Devanagari, copyright 2022 The Noto Project Authors | `launcher/app/src/main/res/font/nepali.ttf` and `remote/dist/assets/nepali.ttf` | [SIL Open Font License 1.1](launcher/NEPALI-FONT-LICENSE-OFL.txt); [upstream](https://github.com/notofonts/devanagari) |

The font files retain their own licenses. The project's MIT notice does not replace the font licenses.

## Bikram Sambat calendar data

The offline month-length table in `launcher/app/src/main/assets/nepali_calendar.csv` and `remote/data/nepali_calendar.csv` is derived from [rjach/sambat](https://github.com/rjach/sambat), distributed under Apache License 2.0. Retained license copies are [launcher/NEPALI-CALENDAR-LICENSE.txt](launcher/NEPALI-CALENDAR-LICENSE.txt) and [remote/data/NEPALI-CALENDAR-LICENSE.txt](remote/data/NEPALI-CALENDAR-LICENSE.txt).

This project converts the source data into a CSV table for offline civil-date display and uses Gregorian fallback beyond the supported table, which ends at BS 2083. The bundled display is not a panchang.

## Build and runtime dependencies

The Gradle wrapper JAR retains its embedded `META-INF/LICENSE`. Gradle and the Android Gradle Plugin are obtained through the normal build process. AndroidX RecyclerView and DynamicAnimation are declared in `launcher/app/build.gradle`; dependency notices remain with their respective distributions. These tools and dependencies are not relicensed by this project.

Native Samsung Sources uses [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0), distributed under [Apache License 2.0](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt), together with its Okio and Kotlin runtime dependencies. JUnit 4.13.2 is used only for offline tests. These dependencies are resolved through Maven by Gradle; their source/binaries are not vendored into the shared source archive. The two PEM files under `launcher/app/src/test/resources/` are deliberately public, self-signed test certificates used only in offline pinning tests; no corresponding private key is included.

Python's standard library powers the local remote. Optional Samsung support uses the separately installed [websockets](https://github.com/python-websockets/websockets) package; its source or binaries are not bundled here. Android platform-tools `adb` is also installed separately and is not included in this repository.

## Artwork and references

The Himalayan landscapes and temple scenes are fictional generated composites. Their generation prompts and provenance are in `prompts/`. No specific real temple, landmark or deity is claimed. Retained project prompts are reference material, not installation instructions.

App names and trademarks identify compatible third-party apps; those apps, services, subscriptions and logos are not granted a license by this repository. No affiliation or endorsement is implied.

## Official compact app marks

Version 17 / 4.4.1-compact-brand-icons includes two unmodified PNGs from the brand owners' official downloads. Copyright and trademark rights remain with Netflix, Inc. and YouTube/Google LLC respectively. These assets are not covered by the project's MIT license.

| Included file under `launcher/app/src/main/res/drawable-nodpi/` | Official source and archive member | SHA-256 of included PNG |
| --- | --- | --- |
| `netflix_official_symbol.png` (991 × 1801) | [Netflix company assets](https://about.netflix.com/en/company-assets); [Netflix_Symbol.zip](https://assets.ctfassets.net/4cd45et68cgf/5vCbQ45TfVUvWgm8eiDOtn/ef1c2431dc81f79ae93fcce649ae796f/Netflix_Symbol.zip), member `02_Netflix_Symbol/01_Netflix_Symbol_RGB/Netflix_Symbol_RGB.png` | `d2c5760ceb2f46d3cedc6fbc3320708d4caa2bf884af2af8d934343315042962` |
| `youtube_official_play.png` (1255 × 1075) | [YouTube icon guide](https://brand.youtube/youtube-icon/); [youtube-icon.zip](https://www.gstatic.com/marketing-cms/78/29/3e68a1414bb28d0b7e47b44c3c91/youtube-icon.zip), member `YouTube_Icon/Digital/01 Red/yt_icon_red_digital.png` | `1027b1b0517727adb9697155a270744381c3ce9b047b1c8bd8a9389dc7d07a83` |

The stored PNG bytes match the archive originals. Display scaling preserves their proportions, colors and transparency. See [the compact-icon note](launcher/BRAND-ICONS.md) for their use alongside native installed-app icons.

The phone remote reuses these same unmodified PNGs at `remote/dist/assets/netflix.png` and `youtube.png`; the same copyright, trademark and license exclusions apply. The remote's mountain/control icon is original SVG artwork.

## Weather service

Current conditions and forecasts come from [Open-Meteo](https://open-meteo.com/), with attribution in both the remote and on-TV panels. See the provider’s [API documentation](https://open-meteo.com/en/docs) and [terms](https://open-meteo.com/en/terms). Requests go to the fixed HTTPS forecast endpoint. No Open-Meteo server or client-library source is vendored in this repository.
