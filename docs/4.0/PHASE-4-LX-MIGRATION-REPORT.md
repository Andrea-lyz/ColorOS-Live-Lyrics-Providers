# 4.0 Phase 4 LX Music / Walnut 迁移报告

记录日期：2026-08-26  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-lx`  
逆向依据：`PlayerSource/lx_Music/LX-MUSIC-SOURCE-INVESTIGATION.md`  
方法模板：`docs/4.0/PLAYER-ADAPTATION-REFERENCE-SALT.md`

## 交付边界

- 新建 `player-lx`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.lx`，`versionCode=1` / `versionName=1.0.0`。
- 官方 LX 与 Walnut 共用一个 Provider APK。LSPosed scope / 调试页目标播放器仅：
  `cn.toside.music.mobile`、`com.lxwalnut.music.mobile`。
  `com.lxnetease.music.mobile`、`.dev`、`com.ikunshare.music.mobile` 不在 4.0 宿主列表里。
  Walnut 进程内仍可回退加载历史 `com.lxnetease...lyric.LyricModule` 类名。
- 最终歌词入口是未混淆的 `LyricModule#setLyric(String, String, String, Promise)`，不是
  JS SDK、网络请求或 `react-native-track-player` 内部 `MetadataManager`。
- 唯一主 `MediaSession` 由 TrackPlayer `MusicService` 持有。Provider 复制当前 host metadata，
  只追加 `lyricInfo`；不保存旧 metadata 后整包覆盖。
- 罗马音是 `setLyric` 第三参，永不写入翻译 lane。翻译走
  `LyricLaneAlignmentPolicy.align` 后进入 `NativeLyricInfoPublisher`。
- 锁屏翻译按钮走公开 `ACTION_TOGGLE_TRANSLATION` CustomAction
  （`PlaybackStateTranslationToggle.prependPublicAction`），不是收藏槽替换。
- 调试开关复用 `provider-core`：`ProviderId.LX`、prefs `lx_provider_debug_prefs`、
  `MODE_WORLD_READABLE` 写入 + Yuki `Context.prefs()` 读取。
- 已删除旧 `:lx-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/lx-music` / `lyricprovider/lx-walnut-music`）。4.0 不适配 NPatch。

## 运行时数据流

```text
LyricModule#setLyric(lrc, tlrc, rlrc)
        ↓
LxLyricDecoder + LyricLaneAlignmentPolicy
        ↓
TrackIdentity（host MediaSession，经蓝牙投影过滤）
        ↓
TrackGenerationPolicy
        ↓
唯一主 MediaSession + 当前 host metadata
        ↓
NativeLyricInfoPublisher
```

## 蓝牙歌词投影（LX 专属）

开启「显示蓝牙歌词」时，LX 把 TITLE 改成当前歌词行，ARTIST 写成
`"${songName} - ${singer}"`。开启「显示完整蓝牙歌词」时，`handleSetLyric` /
`updateNowPlayingTitles({ lyric })` 会把整份 LRC 写进 TITLE。两条路径都走
TrackPlayer 的 titles-only 更新，不带封面 bitmap。这与 Salt 的
`"<Artist> - <Title>"` 方向相反，不得抽成全局规则。

`LxBluetoothLyricMetadataPolicy` 在 generation 之前识别投影：有 stable identity
即可（不要求当前仍 hold 着一份已捕获歌词）。切歌 `handleSetLyric('')` 只清 pending，
同曲 `replaySnapshot` 保留，让随后的 titles-only `setMetadata` 能补回 `lyricInfo`。
真实换曲仍由 `LxHostGenerationController.onTrackChanged` 丢掉旧歌词。

封面必须走 LX 源码里的「在通知栏显示歌曲专辑封面图片」
（`player.isShowNotificationImage` → `updateNowPlayingMetadata({ artwork })` →
TrackPlayer Glide 写入 `ALBUM_ART`）。蓝牙 `updateNowPlayingTitles` 只改标题，
不带封面。Provider **不**做封面快照/URI 写回/HTTP。URI-only 是 Glide 完成前的
原生第一帧。仅当 incoming 已有 bitmap 且为 HARDWARE 或边长大于 240px 时，用
Canvas 画到 software `ARGB_8888`。随后用空 Builder 按类型复制 metadata（禁止
`MediaMetadata.Builder(existing)`），再写 TITLE/ARTIST 和 `lyricInfo`。`setLyric`
补写 session 时以 `MediaSession.controller.metadata` 为底，禁止用过期 URI-only
快照盖掉 Glide 结果。通知栏封面开关关闭时，纯色是 LX 原生产物。

ColorOS 用 TITLE/ARTIST 做锁屏曲目键。投影 metadata 必须先从 LX 自己的
`"${songName} - ${singer}"` 还原歌名/歌手，再 `observeTrack`，并在同曲
titles-only / 暂停还原标题时把 `lyricInfo` replay 回当前 hooked session。

## 歌词早于 metadata

`setLyric` 不含歌曲身份。歌词可能早于 `setMetadata` 到达。Policy：

- 无 host track：最多缓存一条 pending；
- 空白 capture 可绑定到随后的第一份稳定 host track；
- 已绑定 capture 不得发布到另一首歌；
- 空/无时间戳 `setLyric`（切歌 `handleSetLyric('')`）清除 pending；同曲 replay 保留到真实换曲。

## Bridge 登记

- `PlayerTranslationSettings` 指向 `io.github.andrealtb.coloroslyrics.provider.lx`。
- 上述宿主包进入 `BRIDGE_PLAYER_PACKAGES` 与
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 LX / Walnut fork / IKUN 新增 v4 `EXTERNAL_SOURCES`。公开 CustomAction 优先于收藏槽。

## 自动验证

```text
scripts\dev.cmd provider :player-lx:testDebugUnitTest :player-lx:assembleDebug
scripts\dev.cmd bridge testDebugUnitTest
```

设备验收梯子（封面与歌词已在真机通过；后续回归仍按缺失的第一层定位）：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED
        ↓
LYRIC_MODULE_HOOK_INSTALLED
        ↓
TRANSLATION_ACTION_INJECTED reason=public
        ↓
Track identity / generation（无蓝牙投影误换曲）
        ↓
LYRIC_INFO_PUBLISHED / LX_FINAL_PUBLISHED
        ↓
LX_ARTWORK_BINDER_SAFE reason=canvas-software（incoming 已有 Glide bitmap 时）
        ↓
SystemUI NATIVE_LYRIC_RECEIVED artwork bitmap=WxH（parcel ≫ 12KB，不是 bitmap=null）
        ↓
锁屏 / AOD 可见
```

## 明确不做

- 不 Hook `LyricModule#play/pause/setPlaybackRate` 给 SystemUI 推进度。
- 不恢复 Lyricon / `player.setSong` / 位置同步循环。
- 不 Hook TrackPlayer 私有 `MetadataManager`。
- 不把 Salt 复合 ARTIST 解析套到 LX。
- 不 HTTP 拉封面、不维护封面快照、不把 URI 当可显示封面写回。
- 不恢复旧 `:lx-music` 词幕模块或 Bridge v4 `lyricprovider/lx-music` 广播入口。
