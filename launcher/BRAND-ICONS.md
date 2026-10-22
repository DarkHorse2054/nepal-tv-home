# Compact brand icons - v18 / 4.4.2

Netflix and YouTube use compact official marks in the dock: Netflix's red N symbol and YouTube's red play mark with a white triangle. These replace the installed apps' wordmark and bordered artwork in this launcher. Other apps retain their native installed-app icons.

Both PNGs are unchanged files from the brand owners' official download archives. They are rendered on a quiet dark tile with preserved aspect ratio, transparency and colors; there is no AI generation, recoloring or stretching. The visible Netflix symbol is 72% of the tile height; its empty export margin is excluded only during runtime placement, while the original PNG remains unchanged. YouTube retains the original file's clear-space margin and fits within the tile. Runtime bitmap output is bounded to 384 pixels per side.

The change affects launcher artwork only. App packages, subscriptions and launch targets are unchanged. Copyright, trademark ownership, download URLs, archive-member paths and exact PNG hashes are recorded in [THIRD_PARTY.md](../THIRD_PARTY.md); the project's MIT license does not relicense these marks.

Version 18 is installed. Both marks were inspected on the actual TV at rest and selected; the Netflix export margin was excluded during placement to give the visible N the intended size. APK assembly and lint passed. The app, appearance and private Samsung pairing preference files were unchanged by installation. The v16 glass-dock checks remain a separate recorded verification.
