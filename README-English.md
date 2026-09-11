# ColorOS Live Lyrics Providers

An independent Root/LSPosed Provider repository for ColorOS lock-screen lyrics. Every installable
module publishes standard `MediaMetadata["lyricInfo"]` that ColorOS SystemUI can consume directly.
Installing the optional `io.github.andrealtb.lockscreenlyrics` Bridge adds generic word rendering,
AOD, translation controls, appearance settings, and compatibility enhancements.

The v5 release matrix contains 14 installable Provider applications: 12 player-specific modules,
Universal Player Provider, and Readify TTS Provider. Every module uses a libxposed API 102 entry,
static scope, and protective exception handling; the Universal Provider settings app runs separately
while its module logic is limited to `system_server`.

[中文](README.md)

Developer entry points:

- [Provider adaptation guide](docs/4.0/PROVIDER-ADAPTATION-GUIDE.md)
- [Provider 适配技术指南（中文）](docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md)
- [Player-owned `lyricInfo` protocol](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/4.1/docs/PLAYER_INTEGRATION.md)

## v5 Provider matrix

| Player | Gradle module | applicationId | Host package | Adaptation baseline |
|---|---|---|---|---|
| Salt | `:player-salt` | `io.github.andrealtb.coloroslyrics.provider.salt` | `com.salt.music` | `12.3.0-alpha03` |
| Cone / GP | `:player-cone` | `io.github.andrealtb.coloroslyrics.provider.cone` | `ink.trantor.coneplayer` / `ink.trantor.coneplayer.gp` | Formal build `v1.2.0(c77a1ea49)`; GP shares the same profile |
| KuWo | `:kuwo-music` | `io.github.andrealtb.coloroslyrics.provider.kuwo` | `cn.kuwo.player` | `12.2.0.0` |
| LX / Walnut | `:player-lx` | `io.github.andrealtb.coloroslyrics.provider.lx` | `cn.toside.music.mobile` / `com.lxwalnut.music.mobile` | LX `1.8.4`; Walnut `26.07.16` |
| Poweramp | `:player-poweramp` | `io.github.andrealtb.coloroslyrics.provider.poweramp` | `com.maxmpz.audioplayer` | `build-1025-bundle-play` |
| Metrolist | `:player-metrolist` | `io.github.andrealtb.coloroslyrics.provider.metrolist` | `com.metrolist.music` | `13.6.1` |
| KuGou / Lite | `:player-kugou` | `io.github.andrealtb.coloroslyrics.provider.kugou` | `com.kugou.android` / `com.kugou.android.lite` | Standard `20.8.0`; Lite `5.2.61` |
| QQ Music | `:player-qq` | `io.github.andrealtb.coloroslyrics.provider.qq` | `com.tencent.qqmusic` | `20.7.5.8` |
| NetEase / Honor | `:player-netease` | `io.github.andrealtb.coloroslyrics.provider.netease` | `com.netease.cloudmusic` / `com.hihonor.cloudmusic` | Official `9.5.70`; Honor `3.5.20`; modified lite build `9.0.40` |
| Apple Music | `:player-apple` | `io.github.andrealtb.coloroslyrics.provider.apple` | `com.apple.android.music` | `6.5.2` |
| Spotify | `:player-spotify` | `io.github.andrealtb.coloroslyrics.provider.spotify` | `com.spotify.music` | `9.1.78.2208` |
| QiShui | `:player-qishui` | `io.github.andrealtb.coloroslyrics.provider.qishui` | `com.luna.music` | `20.7.0` |
| Universal Player | `:universal-provider` | `io.github.andrealtb.coloroslyrics.provider.universal` | `system` (select target packages in its app) | ColorOS 16 / Android 16 |
| Readify AI | `:player-readify` | `io.github.andrealtb.coloroslyrics.provider.readify` | `com.readin.app` | `3.1.0` |

The adaptation baseline is the host sample used for static reverse engineering, implementation,
and device closure. It does not mean that the Provider supports only that version; host updates
that change obfuscation structures or internal lyric flows still require renewed verification.

Metrolist and Spotify do not expose translations. Other modules use either the public action or
the Bridge five-slot control according to player-specific evidence. QQ Music HD is out of scope.

## Universal Player Provider

Universal Player Provider (`Provider-Universal 1.0.0 (1)`) is for players without a dedicated
adapter that still create a standard Android `MediaSession`. It has static scope only in
`system_server` and receives an explicit target-package list from its settings app; unselected music,
video, and other media apps are never observed, queried for lyrics, or written with `lyricInfo`.

After installing the APK, open **Universal Player Provider**: select target apps on the Players page,
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
the formal 14-app v5 release matrix and Provider bundle.

See [player-readify/README.md](player-readify/README.md) for the protocol, host scope, and current
device-validation limits.

## Acknowledgements

Special thanks to the original [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider)
project and its contributors for the early player adaptations, reverse-engineering ideas, and code
baseline. Although this repository has been comprehensively rebuilt almost end to end around the
standard v5 `lyricInfo` contract, the Root/LSPosed architecture, and each player's internal lyric
flow, its evolution still benefits from the original project's exploration and community work.

Thanks to [Lyrico](https://github.com/Replica0110/Lyrico) and
[Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins) for their open-source work and
inspiration around local music metadata, lyric management, and plugin-based lyric sources.
