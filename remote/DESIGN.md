# Nepal Remote design system

This remote uses the upstream [UI/UX Pro Max skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/blob/main/.claude/skills/ui-ux-pro-max/SKILL.md), used for the October 25, 2026 redesign. Its local search returned Glassmorphism for `glassmorphism dark`, touch spacing/size guidance for `touch target spacing`, and form labels/focus guidance for `form labels keyboard focus`. The generated smart-home system also returned a marketing landing-page structure, which does not fit a remote; the control hierarchy below is a project-specific decision. This document records the applicable guidance and our implementation, rather than persisting that unrelated landing pattern.

## Direction

A quiet Himalayan remote: warm charcoal and sage glass, ivory-gold actions, local mountain scenery, a small Om and English BS date. Keep English labels and minimal text. The dominant control is a sculpted superellipse D-pad with a gold OK button. Samsung display controls occupy their own named surface above Fire TV navigation. Type on TV remains a visible main action. All six app shortcuts remain in a compact floating dock; names appear on pointer hover, keyboard or touch focus, and press. Below 360px the dock reflows to three columns with persistent names.

## Tokens and accessibility

CSS variables in `dist/styles.css` are the source of truth: canvas `#131918`, surface `#202826`, raised surface `#2a3330`, text `#f1f0e8`, muted `#b1bcb5`, quiet `#a1ada6`, accent `#e6cba1`. Text on the nominal opaque surface measures 13.19:1, 7.70:1 and 6.49:1 respectively. Gold OK text measures 6.86:1 at the darker gradient endpoint. Check composited wallpaper states visually too.

Use local Outfit/Nepali fonts and local assets, with no new external runtime requests. Keep official Netflix/YouTube images in their original proportions; other shortcuts are named visual tiles. Core icon geometry uses the existing stroke family. Main targets are at least 44 CSS pixels, mostly 48px or larger, with 8px or greater target gaps. A 3px focus ring, disabled semantics, native dialogs, visible form labels and reduced motion are required. Glass has a readable opaque fallback. Blur is limited to the dock and modal layering.

## Behavior

Preserve fixed action allowlists, session authorization, command serialization and existing physical Home behavior. Do not infer a Samsung power/input state from cached pairing or transport acknowledgment. Status retains Connected / Fire TV offline / Mac offline distinctions; Samsung reports Paired and Last sent. Inline sheet notices keep confirmations/errors in the native dialog top layer. Keep the ordinary global notice for actions on the main remote.

## Verification

Review 320/375/390px phones, landscape, desktop, pairing, typing, Sources and offline recovery. No horizontal overflow or touch targets below 44px were found at the checked sizes. Narrow/short screens may scroll vertically. Native mobile software keyboards and OS text scaling still need real-phone checks. Existing regression tests cover tap/hold cancellation, typing races, dialog dismissal and connection recovery, plus dialog-local notifications. A screenshot fixture simulates devices; it must never be presented as proof of a real TV input or playback state.
