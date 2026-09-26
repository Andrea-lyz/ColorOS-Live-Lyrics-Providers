# LX Artwork Diagnostics

LX artwork probes are read-only and default-off. They do not fetch, replace, cache, resize,
or otherwise change cover behavior. Since 4.1 the Provider itself does not touch artwork either:
identity and lyricInfo are written into LX's own metadata object
(`docs/4.1/LYRICINFO-APPEND-ONLY.zh-CN.md`), so every Provider stage reports the same bitmap
instance. Enable both the LX Provider debug switch and the Bridge media debug area before
capturing a reproduction.

Filter the resulting log with:

```text
ARTWORK_PROBE|LYRIC_INFO_PUBLISHED|LX_FINAL_|NATIVE_LYRIC_RECEIVED
```

## Provider stages

| Stage | Meaning |
| --- | --- |
| `HOST_IN` | Raw `MediaSession#setMetadata` argument from LX/TrackPlayer |
| `HOST_IDENTITY` | The same object after in-place LX Bluetooth title identity normalization |
| `PUBLISH_BASE` / `REPLAY_BASE` | Live metadata selected as publication source |
| `PUBLISH_CANDIDATE` / `REPLAY_CANDIDATE` | The same object after `lyricInfo` was appended in place |
| `HOST_OUT` | Metadata actually passed to the host `MediaSession` call (always the `HOST_IN` object) |

Every bitmap lane reports dimensions, config, allocation bytes, generation/identity, and a
nine-point content sample. `solid` and `near-solid` mean the bitmap itself is already flat;
`varied` means sampled pixels contain image detail. Hardware bitmaps report sampling as
unavailable instead of being copied for diagnostics.

## Bridge and SystemUI stages

| Stage | Meaning |
| --- | --- |
| `PLAYER_SESSION_IN` | Bridge-side observation in the player process, when loaded there |
| `SYSTEMUI_METADATA` | Metadata entering ColorOS lyric loading in SystemUI |
| `SEEDLING_SET_IN` / `SEEDLING_SET_OUT` | `SeedlingMediaData.artworkIcon` mutation |
| `LOADER_IN` / `LOADER_OUT` | Seedling `UriArtworkLoader` input and returned URI/bitmap |
| `SEEDLING_BUNDLE` | Artwork URI and generated background color exported to AOD/plugin consumers |
| `FINAL_IMAGE_VIEW` | Likely media artwork `ImageView` drawable and background |

## First-missing-stage diagnosis

- `HOST_OUT` differs from `HOST_IN` in any bitmap lane: a Provider regression, since no stage may
  touch artwork.
- Provider candidate is `varied`, but `SYSTEMUI_METADATA` is null: framework/Binder metadata loss.
- `SYSTEMUI_METADATA` is `varied`, but `SEEDLING_SET_OUT` has no icon: MediaData-to-Seedling loss.
- Seedling icon is present, but `LOADER_OUT bitmap=null`: icon decoding or content-URI conversion loss.
- Loader output is `varied`, but `FINAL_IMAGE_VIEW` is `ColorDrawable`: final card binding/fallback loss.
- Provider `HOST_IN` is already null with only HTTP(S) URI: TrackPlayer has not supplied a bitmap.
- The first available bitmap is `solid`: the flat image originates upstream of the first stage
  reporting it, rather than from the later ColorOS fallback.
- `LX_FINAL_APPEND_UNSUPPORTED`: the host metadata bundle could not be reached, so identity and
  lyricInfo were skipped instead of rebuilding metadata.

For track-switch tests, capture at least one cached cover, one uncached cover, pause/resume,
seek, screen off/on, and LX Bluetooth lyric title updates in the same log window.
