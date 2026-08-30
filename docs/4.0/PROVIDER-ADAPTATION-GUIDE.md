# 4.0 Provider adaptation guide

[简体中文](PROVIDER-ADAPTATION-GUIDE.zh-CN.md)

This guide defines the main technical path for adding a player to
`ColorOS-Live-Lyrics-Providers`. A Provider is an independent Root / LSPosed module that runs in
the target player process and publishes standard `MediaMetadata["lyricInfo"]` from the player's
own MediaSession.

```text
player-private lyric source
        ↓
player module: process gate → track generation → lyric decode
        ↓
official-append encoder or NativeLyricInfoPublisher
        ↓
player-owned MediaMetadata["lyricInfo"]
        ↓
ColorOS SystemUI (Bridge optional)
```

Provider code never calls Bridge classes, sends a Bridge transport, or assumes Bridge is installed.

## 1. Choose the publication model

Decide this before writing hooks.

### Official append

Use official append when the player already writes a valid `lyricInfo` object. Hook the narrowest
writer/capture boundary and preserve official fields such as `id`, `songId`, `lyricType`, `lyric`,
and `noLyric`. Append only missing lanes and identity/generation diagnostics.

Current examples:

- `QqOfficialLyricInfoEncoder`
- `KuGouOfficialLyricInfoEncoder`
- `KuWoOfficialLyricInfoEncoder`
- NetEase `OFFICIAL_APPEND`

Do not replace a working official payload with the generic constructed schema merely for code
uniformity.

### Constructed payload

Use `NativeLyricInfoPublisher` when the player has no usable official payload. Convert the
authoritative host lyric model into `TrackIdentity` + `List<RichLyricLine>`, then publish a standard
payload transactionally.

Current examples include Apple, LX, Poweramp, Metrolist, Spotify, QiShui, Salt, and Cone. NetEase
9.0.40 selects a constructed profile while the current official builds use append.

## 2. Module scaffold

Each installable module needs:

```text
player-<name>/
  build.gradle.kts
  proguard-rules.pro
  src/main/AndroidManifest.xml
  src/main/assets/xposed_init
  src/main/res/values/arrays.xml
  src/main/kotlin/.../HookEntry.kt
  src/test/kotlin/...
```

Use an application ID under:

```text
io.github.andrealtb.coloroslyrics.provider.<player>
```

Requirements:

- `minSdk=27`, root compile/target SDK values, Java/Kotlin 17 bytecode;
- `xposedmodule=true`, `xposedminversion=93`, `xposedsharedprefs=true`;
- an exported debug-settings launcher activity backed by the module's own preferences;
- `@array/xposed_scope` containing only supported host packages;
- the target player's launcher icon, not another Provider's placeholder;
- release signing from the environment variables already used by the matrix build.

Add the module to `settings.gradle.kts`, root `v5ProviderModules`, and
`release/v5-provider-matrix.json`. The machine contract must list application ID, internal version,
canonical asset name, scopes, process-policy evidence, and validated host versions.

## 3. Process and session gate

Manifest scope is not enough. Determine which host process owns the real playback MediaSession.

Examples of explicit policies:

- QQ: `com.tencent.qqmusic:QQPlayerService`;
- KuGou standard/Lite: only `:support` / `.support`;
- Spotify and QiShui: main process only;
- NetEase: profile selected from package + main/`:play` process.

Reject unrelated push, download, cast, preview, message, remote, or secondary sessions. When the
player legitimately uses several processes, encode the decision in a named `<Player>ProcessPolicy`
or `<Player>RuntimeProfile` and test every accepted/rejected case.

Do not route different products by package name alone when they share one package. Use the
evidenced process/structure profile.

## 4. Track identity and generation

Use `TrackIdentity` and one `TrackGenerationPolicy` per live player/session owner.

Identity should prefer:

1. stable host media ID;
2. title + artist + duration;
3. a named player-specific derivation policy.

Rules:

- real track change increments generation once;
- metadata fill-in for the same track merges without an increment;
- queue preload does not become current-track authority;
- title-only Bluetooth/car lyric projection does not become a new song;
- async work captures ID + generation and rechecks both before publication;
- a new generation clears pending/replay state owned by the previous track.

Never let a late HTTP/JNI/database callback publish against whichever metadata is current when it
returns.

## 5. Capture the narrowest authoritative lyric source

Prefer sources in this order:

1. the player's already-decoded lyric model at its final UI/playback writer;
2. the player's official `lyricInfo` writer/capture object;
3. local sidecar or embedded tags owned by the player;
4. an independent fetch path only when no authoritative host data exists.

Hook discovery may use known names, structural reflection, KavaRef, or DexKit. Keep discovery and
runtime access separate:

- DexKit finds an unknown/obfuscated target;
- a named resolver proves zero/one/multiple candidate behavior;
- KavaRef or guarded reflection accesses the confirmed member;
- cache handles by ClassLoader/host version and release them when appropriate.

Do not select the first method silently. Zero and ambiguous candidate sets are distinct diagnostic
failures.

## 6. Normalize into `RichLyricLine`

The neutral model is:

```kotlin
RichLyricLine(
    begin = absoluteLineStartMs,
    end = absoluteLineEndMs,
    text = primaryText,
    words = primaryWords,
    secondary = translationText,
    secondaryWords = translationWords
)
```

Use the shared parser that matches the source:

- `parser-lrc`
- `parser-qrc`
- `parser-yrc`
- `parser-krc`
- `parser-ttml`

Timing and lane rules:

- all line/word positions are absolute media milliseconds;
- lines are sorted and word times do not move backward;
- repeated timestamps remain separate rows unless the player contract proves otherwise;
- remove invalid/promotional rows before translation alignment;
- each translation is consumed once and aligned to one primary line;
- romaji, pronunciation, and transliteration never enter `secondary`;
- merge unspaced Latin syllable fragments when the host splits one displayed word;
- line-only sources remain line-only; never invent karaoke timing.

## 7. Encode and publish transactionally

For constructed payloads, call `NativeLyricInfoPublisher.publishToPlatformMetadata` only after all
gates pass. It enforces:

- non-null original metadata, non-blank track, non-empty lyric list;
- host package equality;
- current generation and track identity;
- maximum lyric field size;
- complete candidate Parcel measurement;
- no mutation of the supplied builder on rejection.

The default limits are 1,500,000 lyric-field characters and a 512 KiB complete metadata Parcel.
`PAYLOAD_TOO_LARGE` rejects lyric injection while preserving the host metadata path.

For official append, preserve official JSON fields and add canonical extension fields only when
timed content exists:

- `rawLyric`
- `translationLyric`
- `provider`
- `source`
- `sessionGeneration`
- optional stable `trackKey`

Do not create a Bridge envelope.

## 8. Metadata and artwork safety

The metadata arriving at `MediaSession#setMetadata` is authoritative. Copy every host field by type,
including unknown keys, ratings, duration, IDs, artwork URIs, and artwork bitmaps.

On affected ColorOS builds, avoid `MediaMetadata.Builder(existing)`. Use an empty typed builder and
copy fields explicitly. For bitmap transport:

- keep plausible host bitmaps;
- redraw HARDWARE or oversized bitmaps to software `ARGB_8888` only when required for Binder;
- preserve URI-only first frames when the host has not decoded a bitmap yet;
- attach pending lyricInfo to the incoming host metadata instead of replaying a stale snapshot;
- do not fetch, invent, or restore artwork from another track.

Ignore cast/auxiliary sessions. A payload must never move to a session with a different live track
identity.

## 9. PlaybackState and translation action

Keep host PlaybackState semantics. Never fabricate PLAYING, position, speed, or update time to wake
SystemUI.

If the player supports the public translation action, use `PlaybackStateTranslationToggle`:

```text
io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION
```

It copies the host state through an empty builder, preserves actions/extras, avoids duplicates, and
returns a new instance so ColorOS can rebind. Do not inject it into players whose official action row
uses another ownership model; those modules may use the proven favorite-slot presentation on the
Bridge side.

## 10. Debug and privacy

Use one `ProviderId` and one module-owned debug preferences file. Open module preferences with
`MODE_WORLD_READABLE` so the hooked player can read the exported LSPosed preferences. The switch is
off by default and must not change hook, identity, generation, parsing, or publication behavior.

Structured events use:

```text
[CLL] level=INFO component=provider/<player> area=<area> event=<event>
```

Write key events to logcat and the Xposed framework sink when debug is enabled. Use stable event
names such as `PROCESS_READY`, `TRACK_BOUND`, `LYRIC_CAPTURED`, `LYRIC_INFO_PUBLISHED`, and a
classified rejection reason.

Never log complete lyrics, authorization/client tokens, cookies, URL queries, stable raw media IDs,
or private local paths. Use `DiagnosticHasher`, `SensitiveFieldRedactor`, and throttling.

## 11. Tests

Every module should cover:

- accepted/rejected packages and processes;
- same track vs real track change;
- generation invalidation and late-result rejection;
- parser timing/lane classification;
- translation vs pronunciation;
- official-field preservation or constructed JSON shape;
- metadata copy and artwork policy;
- pending/replay cancellation;
- public action preservation when used;
- redacted diagnostics.

Run the focused module first, then the full matrix:

```powershell
.\gradlew.bat :player-<name>:testDebugUnitTest :player-<name>:assembleDebug
.\gradlew.bat testV5Matrix assembleV5MatrixDebug
```

Release/R8 validation uses the signed matrix workflow. Missing signing environment must fail during
configuration; a release must never fall back to debug signing.

## 12. Device validation ladder

Validate the Provider without Bridge first, then with Bridge:

1. module/process ready and debug preference applied;
2. one authoritative track bound to one generation;
3. lyric captured and classified as line/word/no-lyric;
4. native `lyricInfo` published on the correct MediaSession;
5. stock SystemUI lock-screen lyrics visible with Provider only;
6. Bridge adds rendering/AOD/translation behavior without duplicate submission;
7. pause/resume, seek, rapid skip, same-track replay, lock/unlock, and AOD;
8. artwork, notification controls, Bluetooth/car metadata, and action row remain native;
9. no stale result after a three-track rapid skip;
10. debug off produces no high-frequency trace; debug on is structured and redacted.

Record host APK version/SHA, package/process, device/ROM/SystemUI/LSPosed versions, exact Provider
commit/APK hash, and the final user-confirmed result in a migration report.

## 13. Release contract

Before a Provider enters the suite:

- add it to `release/v5-provider-matrix.json`;
- make `scripts/validate-v5-release-contract.ps1` pass;
- keep the root module count exact;
- use the canonical `ColorOS-Live-Lyrics-Provider-<Name>-v<suite>.apk` asset;
- pass `testV5Matrix`, release/R8, package/version/scope/certificate checks, and device validation.

The current 4.0 release has exactly 12 Provider APKs. Adding a thirteenth module is a deliberate
release-contract change, not an automatic file discovery.

## 14. Reference material

- [Salt reference implementation](PLAYER-ADAPTATION-REFERENCE-SALT.md)
- [v5 migration map](PHASE-0-MIGRATION-MAP.md)
- [final repository cleanup](REPOSITORY-CLEANUP-REPORT.md)
- per-player `PHASE-4-*-MIGRATION-REPORT.md` files
- [player-owned `lyricInfo` protocol](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/4.0/docs/PLAYER_INTEGRATION.md)
