# 4.0 Phase 4 Metrolist 迁移报告

记录日期：2026-08-27  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-metrolist`  
逆向依据：`PlayerSource/Metrolist/METROLIST-SOURCE-AND-V4-MIGRATION-INVESTIGATION.md`  
方法模板：`docs/4.0/PLAYER-ADAPTATION-REFERENCE-SALT.md`、`:player-lx`、`:player-poweramp`  
真机日志：`logs/lyrics-log-20260827-030122.txt`、`logs/lyrics-log-20260827-032735.txt`

## 交付边界

- 新建 `player-metrolist`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.metrolist`，`versionCode=1` /
  `versionName=1.0.0`。
- LSPosed scope / 调试页目标播放器仅 `com.metrolist.music`。
- 歌词检索只跟宿主 `MusicService.currentMediaMetadata`（`id` + `title` 必需；
  时长是秒，`-1` 未知）。禁止用平台 `MediaSession#setMetadata` 第一帧开搜。
- 独立 `MetrolistLyricsFetcher` 按宿主 DataStore 顺序请求 BetterLyrics /
  LrcLib / KuGou。不要反射宿主 `LyricsHelper.getLyrics`。
- `lyricInfo` 经空 Builder 全量拷贝后由 `NativeLyricInfoPublisher` 写入。
  Coil 封面 URI-only 不得叠加；bitmap 到达时附着到同一次 host `setMetadata`。
  不要从 `setMetadata` before-hook 按 registry 快照 drain pending。
- 不支持翻译，不注入 `ACTION_TOGGLE_TRANSLATION`。
- 调试开关复用 `provider-core`：`ProviderId.METROLIST`、prefs
  `metrolist_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:metrolist-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/metrolist-music`）。4.0 不适配 NPatch。旧 `LyricProvider`
  仓库的 `:metrolist-music` 仍留给 3.x 发布，不是本切片编辑目标。

## 运行时数据流

```text
MusicService#onEvents → currentMediaMetadata (id + title)
        ↓
TrackIdentity（durationMs = host seconds × 1000；-1 → 0）
        ↓
MetrolistLyricFetchGate（每代最多一次；身份不完整不 latch）
        ↓
BetterLyrics → LrcLib → KuGou（DataStore 顺序；未实现源跳过）
        ↓
MetrolistLyricDecoder（TTML / 纯 LRC / KRC）
        ↓
pending until plausible ALBUM_ART bitmap
        ↓
NativeLyricInfoPublisher → session.setMetadata
```

## 真机收口

- `lyrics-log-20260827-030122.txt`：平台 `reason=metadata` 把 generation latch
  住，后续切歌 `NO_LYRIC`。已改为只从 `music-service` 开搜。
- `lyrics-log-20260827-032735.txt`：Hands To Myself BetterLyrics HIT 后
  `PENDING_ARTWORK` → `PENDING_DRAINED` 且无 `PUBLISHED`。Coil bitmap 写入
  的 before-hook 用 registry 快照 drain，live `controller.metadata` 仍是
  URI-only，`allowPending=false` 丢掉 pending。已改为附着 incoming metadata，
  后续 drain 只看 live controller。

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.metrolist`，`supportsTranslation=false`。
- `com.metrolist.music` 留在 `BRIDGE_PLAYER_PACKAGES`。
- 不加入 `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 Metrolist 新增 v4 `EXTERNAL_SOURCES`。

## 自动验证

```text
scripts\dev.cmd provider :player-metrolist:testDebugUnitTest :player-metrolist:assembleDebug
```

设备验收梯子：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED
        ↓
TRACK_BOUND reason=music-service（带 title / durationMs）
        ↓
LYRIC_PROVIDERS_SELECTED / LYRIC_PROVIDER_TRY / HIT|MISS
        ↓
METROLIST_FINAL_PENDING_ARTWORK（仅 URI-only 封面时）
        ↓
METROLIST_HOST_METADATA_LYRIC_INFO_ATTACHED / METROLIST_FINAL_PUBLISHED
        ↓
Accepted bridge lyricInfo / NATIVE_LYRIC_RECEIVED
        ↓
锁屏 / AOD 可见；连切下一首不得再出现 HOST_LYRICS_HELPER_* 或 reason=metadata 开搜
```

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播。
- 不注入翻译按钮，不覆盖收藏 / 桌面歌词槽。
- 不实现 Paxsenix / LyricsPlus / YouTubeSubtitle；顺序里遇到即跳过。
- 不改 KuWo / LX / Poweramp 封面路径。
- 不在本切片改 Bridge 统一发布 workflow（仍从旧 `LyricProvider` 编
  `:metrolist-music`）；4.0 正式发布时再切到本仓库 `:player-metrolist`。
