# Nuvio Clean TV

A personal fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) for a TCL 65C6K (Google TV 14):
the CleanYourTV launcher with an Apple TV-style layout, whose Home is filled from the owner's
[Nuvio](https://github.com/NuvioMedia/NuvioTV) account. The official Nuvio app stays installed and unmodified:
the launcher reads the account through Nuvio's documented public API (https://nuvio.tv/docs) and opens titles in
Nuvio with its `nuvio://detail/<movie|series>/<id>` link, so details, sources and the player are Nuvio's own.

## What is here

| Path | What it is |
|---|---|
| `prototype/index.html` | Clickable prototype of the Home screen (open in a browser; arrow keys, Enter, Esc). Its "My TV today" switch shows only what works with Nuvio as it is. |
| `prototype/home-screen-options.html` | The first ten layout options, and the Gallery Shelf mockups. |
| `themes/gallery-shelf/` | Gallery Shelf theme for the stock launcher: rotating photo wallpaper, frosted dock, Apple TV-style top shelf. |
| `nuvio-home/` | **The launcher** (phase 1): sidebar, Catalog page from the Nuvio account, folders, Applications, sign-in and profiles. Build: `cd nuvio-home && ./gradlew assembleRelease` (or Gradle 8.x). |
| `nuvio-link-test/` | Standalone test app that proved the approach on the TV (sign-in, profiles, addons, Continue Watching, Home rows and collections, opening titles in Nuvio). |

## Test results on the TV (2026-11-03)

Sign-in, 3 profiles and 5 addons read; Continue Watching 12 of 12 titles with artwork; Nuvio Home mirrored in
order (22 rows, 17 of them collections: 692 folders, all addon-catalog sources); films and series open in Nuvio.
A cold load took about 8.5 s, so the launcher must cache Home and refresh in the background.

## Phase 1 (built 2026-11-03)

`nuvio-home/`: frosted sidebar (Catalog, Applications, Inputs, Clock, Settings, Nuvio account, Nuvio Settings),
Catalog page with Continue Watching and the Nuvio Home rows and collections in Nuvio's order, film details over a
blurred backdrop, folder pages, Applications grid (6 columns), Nuvio sign-in and profile switching, Home saved on
the TV and refreshed in the background. Installs as a normal app; Inputs, Clock and Settings are placeholders.

## Next

1. Base launcher: Gallery Shelf look and sidebar (Catalog, Applications, Inputs, Clock, Settings, Nuvio account,
   Nuvio Settings).
2. Nuvio module: sign-in and profile picker, Home rows and collections, Continue Watching, folder screens,
   focused catalog, blurred backdrop, OK opens Nuvio.
3. Settings: focused catalog and position, rows on Home, sidebar blur.
4. Install as Home on the 65C6K with `launcher/build-and-install.sh`, list in `examples/tcl-65c6k-google-tv`.

Nuvio has no link to open its settings or account screens yet; both open Nuvio's home until it does.
