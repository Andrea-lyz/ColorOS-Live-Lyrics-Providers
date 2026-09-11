# Readify Provider (experimental)

Version-pinned adapter for Readify AI 3.1.0 (`com.readin.app`). Requires LSPosed
modern API 102 and the companion Bridge sentence-window-v1 change.

Only hooks the Readify process. Publishes `lyricInfo` on the one active host
MediaSession, preserving metadata, playback state, artwork, and controls. No
second session, system hooks, network permission, embedded APK or signing key.

Chunk AI reads the current utterance from packet/token callbacks and up to two
neighbors on each side from the host's existing cache. Supports both
ReadiumTtsContentSession and NativeChunkContentSession. It never advances the
host iterator or calls extra synthesis requests. Traditional Readium TTS keeps
single-sentence output. Missing cache falls back to one sentence. Unknown host
versions fail closed. Stop, release and track changes clear owned text.

Wire identity follows provider-core `id|title|artist|durationSeconds`. The
sentence-window-v1 extension supplies currentLine and ordered row slots; it does
not claim word timestamps or estimate speech duration. Content is bounded to
1600 code points per row and five rows, with no titles/body text in diagnostics.

This initial port keeps the device-tested Java/libxposed adapter self-contained,
rather than converting hooks to YukiHookAPI during review. It does not yet join
the official release matrix or change signing/builds for existing modules.

## Validation and limitations

The tester confirmed working multi-line lyrics, current-sentence hold, and long
text on OnePlus 13 / Android 16 / Readify AI 3.1.0 online speech, using the local
`de.sqlsec.readifylyrics` test package plus the companion Bridge. The renamed
module in this PR is **not yet device-tested**. Do not enable both packages at once.

Tests cover text/Unicode normalization, pre-service version gating, unsupported
versions, diagnostic privacy, bounded windows, and stable track keys. Host hook
symbols were checked against the actual 3.1.0 DEX; no host APK is distributed.

Build with JDK 21 and the repository SDK/toolchain:

```sh
./gradlew :player-readify:testDebugUnitTest :player-readify:lintDebug :player-readify:assembleDebug
```

Public release integration and an official-package device test remain follow-up
work after review of the companion Bridge protocol.

Companion Bridge proposal: https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/pull/43

PR checkout validation: JDK 21 / AGP 9.1.1 / API 37; module tests (3 JUnit cases), lint and debug APK build passed. APK entry/scope and absence of test fixtures were checked.
