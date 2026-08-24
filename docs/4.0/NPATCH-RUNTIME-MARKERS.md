# NPatch runtime marker and debug configuration

`provider-core` uses explicit build markers rather than root detection to distinguish embedded NPatch execution from a root Xposed module.

## Build markers

Root module builds retain the library defaults:

```kotlin
manifestPlaceholders["colorosLyricsNpatchEmbedded"] = "false"
manifestPlaceholders["colorosLyricsProviderDebugEnabled"] = "false"
```

An NPatch-embedded player variant must override the first marker:

```kotlin
manifestPlaceholders["colorosLyricsNpatchEmbedded"] = "true"
manifestPlaceholders["colorosLyricsProviderDebugEnabled"] = "false" // opt in only for a debug artifact
```

The embedded build must merge `provider-core` resources so the host also contains:

```text
string/npatch_marker = coloros-live-lyrics-provider-v5
bool/provider_debug_enabled = false
```

Manifest and resource markers are both accepted. Simultaneous Xposed-active and NPatch markers are treated as a transport conflict and resolve to `UNKNOWN`, which disables publication.

## Initialization order

Root entry points call `RuntimeModeResolver.notifyXposedHookActive()` before the first `resolve(hostContext)`. NPatch entry points do not call it and rely on the embedded marker.

After mode resolution, each player configures diagnostics through its own `ProviderId`:

```kotlin
ProviderDebugConfig.configureDiagnostics(
    mode = resolution.mode,
    provider = ProviderId.SALT,
    rootSource = ProviderDebugConfig.sharedPreferencesSource(moduleContext),
    embeddedSource = ProviderDebugConfig.embeddedMarkerSource(hostContext)
)
```

ROOT mode reads module-owned settings. NPatch mode reads only packaged host markers and never assumes access to an independently installed Provider APK's preferences. `UNKNOWN` always defaults to debug disabled.
