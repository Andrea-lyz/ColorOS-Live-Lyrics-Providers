# ColorOS Live Lyrics Providers

An independent Root/LSPosed Provider repository for ColorOS lock-screen lyrics. Every installable
module publishes standard `MediaMetadata["lyricInfo"]` that ColorOS SystemUI can consume directly.
Installing the optional `io.github.andrealtb.lockscreenlyrics` Bridge adds generic word rendering,
AOD, translation controls, appearance settings, and compatibility enhancements.

The v5 release matrix contains 13 installable Provider applications: 11 player-specific modules,
Universal Player Provider, and Readify TTS Provider. Every module uses a libxposed API 102 entry,
static scope, and protective exception handling; the Universal Provider settings app runs separately
while its module logic is limited to `system_server`.

Since 5.0 each Provider is released on its own at its own version (tag `<module>-v<versionName>`) and distributed
through the Bridge store's signed catalog: users install and update from the Bridge **Store**, and a dedicated
Provider can request its LSPosed scope from its card. The Bridge Release no longer carries Provider APKs or a ZIP.

An optional, independently installed [Dynamic Artwork Provider](#dynamic-artwork-provider-optional)
(`:artwork-provider-am`) is also part of this repository. It is not a lyric Provider and is built,
installed, and released on its own.

[中文](README.md)

Developer entry points:

- [Provider adaptation guide](docs/4.0/PROVIDER-ADAPTATION-GUIDE.md)
- [Provider 适配技术指南（中文）](docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md)
- [Player-owned `lyricInfo` protocol](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/main/docs/PLAYER_INTEGRATION.md)

## v5 Provider matrix

| Player | Gradle module | applicationId | Host package | Adaptation baseline |
|---|---|---|---|---|
| Salt | `:player-salt` | `io.github.andrealtb.coloroslyrics.provider.salt` | `com.salt.music` | `12.7.0-beta01` |
| Cone / GP / GO | `:player-cone` | `io.github.andrealtb.coloroslyrics.provider.cone` | `ink.trantor.coneplayer` / `ink.trantor.coneplayer.gp` / `ink.trantor.coneplayer.go` | Formal build `v1.2.0(c77a1ea49)`; GP shares the same profile; GO `v1.3.0(0ea0fd837)` |
| KuWo | `:kuwo-music` | `io.github.andrealtb.coloroslyrics.provider.kuwo` | `cn.kuwo.player` | `12.2.0.0` |
| LX / Walnut | `:player-lx` | `io.github.andrealtb.coloroslyrics.provider.lx` | `cn.toside.music.mobile` / `com.lxwalnut.music.mobile` | LX `1.8.4`; Walnut `26.07.16` |
| Poweramp | `:player-poweramp` | `io.github.andrealtb.coloroslyrics.provider.poweramp` | `com.maxmpz.audioplayer` | `build-1025-bundle-play` |
| KuGou / Lite | `:player-kugou` | `io.github.andrealtb.coloroslyrics.provider.kugou` | `com.kugou.android` / `com.kugou.android.lite` | Standard `20.8.0`; Lite `5.2.61` |
| QQ Music | `:player-qq` | `io.github.andrealtb.coloroslyrics.provider.qq` | `com.tencent.qqmusic` | `20.7.5.8` |
| NetEase / Honor | `:player-netease` | `io.github.andrealtb.coloroslyrics.provider.netease` | `com.netease.cloudmusic` / `com.hihonor.cloudmusic` | Official `9.5.70`; Honor `3.5.20`; modified lite build `9.0.40` |
| Apple Music | `:player-apple` | `io.github.andrealtb.coloroslyrics.provider.apple` | `com.apple.android.music` | `6.5.2` |
| Spotify | `:player-spotify` | `io.github.andrealtb.coloroslyrics.provider.spotify` | `com.spotify.music` | `9.1.78.2208` |
| QiShui | `:player-qishui` | `io.github.andrealtb.coloroslyrics.provider.qishui` | `com.luna.music` | `20.7.0` |
| Universal Player | `:universal-provider` | `io.github.andrealtb.coloroslyrics.provider.universal` | `system` (select target packages in its app) | ColorOS 16 / 17 |
| Readify AI | `:player-readify` | `io.github.andrealtb.coloroslyrics.provider.readify` | `com.readin.app` | `3.1.0` |

The adaptation baseline is the host sample used for static reverse engineering, implementation,
and device closure. It does not mean that the Provider supports only that version; host updates
that change obfuscation structures or internal lyric flows still require renewed verification.

Spotify does not expose translations. Other modules use either the public action or
the Bridge five-slot control according to player-specific evidence. QQ Music HD is out of scope. Metrolist is no longer in the release matrix as of 5.0
(the `player-metrolist` source stays in the repository but is neither built nor released).

## Universal Player Provider

Universal Player Provider (`Provider-Universal 1.1.5 (7)`) is for players without a dedicated
adapter that still create a standard Android `MediaSession`. It has static scope only in
`system_server` and receives an explicit target-package list from its settings app; unselected music,
video, and other media apps are never observed, queried for lyrics, or written with `lyricInfo`.

1.1.1 fixes a module load failure on ColorOS 17 (Android 17): the C17 `MediaSessionRecord$SessionStub`
no longer has the synthetic `this$0` field and holds `mRecord` (a `WeakReference`) instead, so 1.1.0
and earlier threw `NoSuchFieldException` and the enabled module answered no player at all. 1.1.1
resolves the field by name first and falls back to its type; ColorOS 16 and earlier are unchanged.

1.1.2 opens the official players that already have a dedicated Provider (HeyTap Music, QQ Music,
KuGou and KuGou Lite, NetEase Cloud Music and its Honor build) for manual selection on the Players
page; only KuWo stays unavailable. **Enable only one Provider per player**: use either its dedicated
Provider or the Universal Provider. With both on, each writes `lyricInfo` and they overwrite each other.

1.1.4 is the first release distributed through the Bridge store and adds a status endpoint, so the Bridge can show
whether it is enabled in LSPosed without root.

1.1.5 adds TTML and duet support (needs Bridge 5.1.0): Apple Music now reads word-timed TTML from the Better Lyrics
cache, and can optionally read official lyrics with an Apple Music subscription signed in inside the app. It adds a
KuGou source and a "TTML first" switch, extra QQ Music search fallbacks, and shows each lyric's real format on Home,
in recent history and in search results.

After installing it from the Bridge store, open **Universal Player Provider**: select target apps on the Players page,
set lyric-source priority on Sources, and enable word timing, translations, raw lyrics, or sanitized
diagnostics as needed. The module preserves existing host metadata and appends standard
`MediaMetadata["lyricInfo"]` only to a selected active MediaSession. ColorOS SystemUI can consume it
directly; with Bridge installed, Bridge continues to own SystemUI rendering, AOD, and translation
controls.

Never enable a dedicated Provider and Universal Provider for the same player. Disable Bluetooth,
car-lyrics, or similar features that overwrite the media title with the current lyric line, otherwise
first-track matching, caching, and track-change recognition can be unreliable. Issue reports need
sanitized logs, the selected package, and reproduction steps; never include complete lyrics, cookies,
tokens, or personal media paths.

## Architecture

- `provider-core`: TrackIdentity, generation, standard `lyricInfo` publication, debug and diagnostics.
- `reflection-core`: bounded reflection and DexKit discovery.
- `parser-lrc/qrc/yrc/krc/ttml`: neutral lyric parsers.
- `share:extensions-kt`, `share:extensions-android`, `share:lrckit` and
  `share:yrckit`: compatibility helpers still used by KuWo/NetEase; they are not installable apps.

`io.github.proify.lyricon.lyric:model` remains only as a compatibility DTO dependency for
KuWo/NetEase.

This repository does not distribute Lyricon Providers. Obtain that product path from the
[original LyricProvider project](https://github.com/tomakino/LyricProvider), and report Lyricon
display/product issues there. Bridge and this repository handle only the ColorOS native
`lyricInfo` path.

## Adding a player

Do not copy a large existing Hooker and immediately add it to the matrix. Follow the
[Provider adaptation guide](docs/4.0/PROVIDER-ADAPTATION-GUIDE.md) to establish:

1. official append vs constructed publication;
2. authoritative process/MediaSession, track identity, and generation;
3. lyric lanes, artwork, PlaybackState, and translation-action ownership;
4. debug/privacy, unit tests, and the device-validation ladder;
5. the explicit `release/v5-provider-matrix.json` contract.

## Build

JDK 21 and an Android SDK are required:

```powershell
.\gradlew.bat assembleV5MatrixDebug
.\gradlew.bat assembleV5MatrixRelease
.\gradlew.bat :player-readify:testDebugUnitTest :player-readify:lintDebug :player-readify:assembleDebug
```

Single-module example:

```powershell
.\gradlew.bat :player-qishui:assembleDebug
```

Release signing uses `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

## Releasing to the store

1. Raise versionName/versionCode in both `release/v5-provider-matrix.json` and the module's `build.gradle.kts`, write
   bilingual notes in `release/notes/<module>/<versionName>.md`, and merge into `main`.
2. Tag that commit `<module>-v<versionName>` and push it. GitHub does not trigger workflows when more than three tags
   are pushed at once, so push a batch in groups of three or fewer.
3. `provider-store-release.yml` builds, signs and publishes an immutable Release, re-checks the downloaded hash, and,
   after approval of the protected `provider-catalog` environment, signs the next catalog and advances `store-catalog`.
   A failure at any step leaves the published catalog unchanged.
4. After changing the matrix, run local checks with `--rerun-tasks` so tests that read the matrix do not reuse cached results.

## Documentation and provenance

- Provider adaptation guide: `docs/4.0/PROVIDER-ADAPTATION-GUIDE.md`
- Provider 适配技术指南: `docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md`
- Migration matrix: `docs/4.0/PHASE-0-MIGRATION-MAP.md`
- Per-player device closure: `docs/4.0/PHASE-4-*-MIGRATION-REPORT.md`
- Repository cleanup and final build: `docs/4.0/REPOSITORY-CLEANUP-REPORT.md`
- Original LyricProvider baseline and attribution: `NOTICE`

Licensed under Apache-2.0. Retained third-party provenance and contributor attribution remain in
source headers, `NOTICE` and the migration reports.

## Readify TTS Provider

`:player-readify` (`io.github.andrealtb.coloroslyrics.provider.readify`) is an API 102 TTS adapter
for Readify 3.1.0. It requires the companion Bridge `sentence-window-v1` support and is included in
the formal 13-app v5 release matrix and distributed through the Bridge store.

See [player-readify/README.md](player-readify/README.md) for the protocol, host scope, and current
device-validation limits.

## Dynamic Artwork Provider (optional)

`:artwork-provider-am` (`io.github.andrealtb.artwork.am`) is an optional standalone app that gives
the lock screen a square motion cover: it queries Apple Music and optional NetEase concurrently,
prefers the AM download, verifies the square video, and hands it to an authorized Bridge over
`artwork-contract` v1. It is not an Xposed module, it never injects into a player, and it is not
part of the v5 lyric matrix above. Without it the lock screen keeps the official static cover.

- First run: turn the source on inside the app (off by default, unmetered networks by default),
  then pick and authorize it in the Bridge under "Appearance → Dynamic artwork".
- Fully independent of the lyric Providers: install, update, or remove it on its own; the lyric
  path is unaffected.
- Source version 1.0.6 (versionCode 10), signed with the same release key as the lyric Providers. During 4.5.x it
  shipped with the formal Bridge release as its own asset; since 5.0 it is distributed through the Bridge store
  (tag `artwork-provider-am-v1.0.6`) with the same 1.0.6 code.
- 1.0.6 (with Bridge 4.5.1): the manual binding page can import your own video as a local album's motion
  cover, keeping a square 1080 SDR H.264 picture as is and centre-cropping and compressing anything else to
  1080; manual search reads the selected storefront's Apple Music album shelf; same-titled editions get a
  "Several editions" status and an automatic pick at Loose; symbol-named albums such as `=` and `÷ (Deluxe)`
  bind and stay apart.
- The settings page can test reachability of the Apple Music storefront and shows the latency or a
  timeout, so you can tell whether the network can reach the source.
- NetEase requires opt-in and QR sign-in, with a separate search field and cover thumbnails. Native 1280 video needs Bridge 4.5.0 or later.
- Source chain and development entry point: [artwork-provider-am/README.md](artwork-provider-am/README.md).

`artwork-contract/` mirrors the protocol module of the same name in the Bridge repository; files
under `src/` must stay byte-identical to it, and `scripts/verify-artwork-contract.ps1` checks that
whenever both checkouts are available (Gradle task `verifyArtworkContract`).

## Acknowledgements

Special thanks to the original [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider)
project and its contributors for the early player adaptations, reverse-engineering ideas, and code
baseline. Although this repository has been comprehensively rebuilt almost end to end around the
standard v5 `lyricInfo` contract, the Root/LSPosed architecture, and each player's internal lyric
flow, its evolution still benefits from the original project's exploration and community work.

Thanks to [Lyrico](https://github.com/Replica0110/Lyrico) and
[Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins) for their open-source work and
inspiration around local music metadata, lyric management, and plugin-based lyric sources.
