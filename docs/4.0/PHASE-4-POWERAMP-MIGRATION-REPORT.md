# 4.0 Phase 4 Poweramp 迁移报告

记录日期：2026-08-26  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-poweramp`  
逆向依据：`PlayerSource/Poweramp/POWERAMP-SOURCE-INVESTIGATION.md`  
方法模板：`docs/4.0/PLAYER-ADAPTATION-REFERENCE-SALT.md`、`:player-lx`

## 交付边界

- 新建 `player-poweramp`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.poweramp`，`versionCode=1` /
  `versionName=1.0.0`。
- LSPosed scope / 调试页目标播放器仅 `com.maxmpz.audioplayer`。
- 歌词入口是公开广播 `com.maxmpz.audioplayer.TRACK_CHANGED`，不是混淆的
  `LyricsChain2`（`p000.o70`）。Poweramp 后台播放不加载歌词，Provider 必须自己读。
- 本地来源顺序：同目录 sidecar `.lrc`，然后 TagLib 内嵌 `LYRICS` /
  `UNSYNCEDLYRICS` / `USLT`。无时间戳文本不写 `lyricInfo`。
- 本切片不恢复词幕在线搜索、`PowerampSaltLyricBridge` 或 v4 广播。
- 主 MediaSession tag 为 `"Poweramp"`；`"CastMediaSession"` 隔离。
- `lyricInfo` 经空 Builder 全量拷贝后由 `NativeLyricInfoPublisher` 写入。禁止
  `MediaMetadata.Builder(existing)`。切歌第一帧是 `android.resource` 占位 URI +
  空 bitmap，必须等到第二帧真实 `ALBUM_ART` 再叠加。
- 锁屏翻译按钮走公开 `ACTION_TOGGLE_TRANSLATION`
  （`PlaybackStateTranslationToggle.prependPublicAction`）。改写 host
  `setPlaybackState` 入参时用空 Builder 逐字段拷贝，禁止
  `PlaybackState.Builder(existing)`，并且每次都返回新实例。每代最多 poke
  一次：仅在 live=`PLAYING` 且已有 `lyricInfo` 时同步写入，暂停立即放弃，
  禁止 `Handler.post`。`lyrics-log-20260826-070940.txt` 的延迟重放把进度写成
  0；`lyrics-log-20260826-072033.txt` 的 Bloodstream / False God 是切歌保持
  PLAYING、只 `setMetadata`，ColorOS 不重绑收藏槽。
- 调试开关复用 `provider-core`：`ProviderId.POWERAMP`、prefs
  `poweramp_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:poweramp-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/poweramp-music`）和 `PowerampLocalAdapter`。4.0 不适配 NPatch。

## 运行时数据流

```text
宿主 sendStickyBroadcast(TRACK_CHANGED) / 注册时 sticky receiver
        ↓
TRACK_CHANGED (id, path, title, artist, album, durMs)
        ↓
TrackIdentity（mediaId 末段与广播 id 对齐）
        ↓
sidecar .lrc → TagLib 内嵌歌词
        ↓
PowerampLyricDecoder（同时间戳行保留为 secondary；仅在有独立翻译轨时才走 LyricLaneAlignmentPolicy）
        ↓
TrackGenerationPolicy
        ↓
主 MediaSession + 当前 host metadata（须有 plausible bitmap）
        ↓
NativeLyricInfoPublisher
```

## 两阶段封面

Poweramp 切歌立刻 `ALBUM_ART=null` 并写入 `android.resource://.../drawable/...`
占位 URI，异步解码后再发带真实 bitmap 的第二次 `setMetadata`。旧 Provider 在
重打包 metadata 时丢掉 bitmap，锁屏退化为纯色。

4.0 规则：

- URI-only（含 `android.resource` / `content://` 占位）不得叠加 `lyricInfo`。
- 无任何封面 URI 时允许发布（原生无封面）。
- pending 歌词在第二帧 bitmap 到达时附着到同一次 host `setMetadata`。
- 已有 bitmap 且为 HARDWARE 或边长 >240px 时 Canvas 画到 software ARGB_8888。
- 不 HTTP 拉封面、不 LRU 快照、不发明封面。

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.poweramp`。
- `com.maxmpz.audioplayer` 留在 `BRIDGE_PLAYER_PACKAGES` 与
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 Poweramp 新增 v4 `EXTERNAL_SOURCES`。公开 CustomAction 优先于收藏槽。

## 自动验证

```text
scripts\dev.cmd provider :player-poweramp:testDebugUnitTest :player-poweramp:assembleDebug
scripts\dev.cmd bridge testDebugUnitTest
```

设备验收梯子：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED
        ↓
TRACK_CHANGED_SEND_HOOK_INSTALLED
        ↓
TRACK_CHANGED_RECEIVER_INSTALLED
        ↓
TRANSLATION_ACTION_INJECTED reason=public
        ↓
TRANSLATION_ACTION_POKED（切歌保持 PLAYING 时；暂停不得出现）
        ↓
TRACK_BOUND / POWERAMP_FINAL_PENDING_ARTWORK
        ↓
POWERAMP_HOST_METADATA_LYRIC_INFO_ATTACHED / POWERAMP_FINAL_PUBLISHED
        ↓
ARTWORK_PROBE HOST_OUT album=WxH（不是 1x1 / solid）
        ↓
锁屏 / AOD 可见
```

## 最终真机收口（2026-08-29）

全量审查首次将动态接收器改为 `RECEIVER_NOT_EXPORTED` 后，
`lyrics-log-20260829-014011.txt` 显示注册时的历史 sticky 事件可以取词，但后续切歌没有新的
`TRACK_BOUND`，只由 MediaSession metadata 推进 generation 并丢弃旧 pending。最终修复保留
非导出接收器，同时在 Poweramp 宿主进程内截获其自身
`ContextWrapper.sendStickyBroadcast(Intent)` 的 `TRACK_CHANGED`，并以 `ts` + track key
去重 hook/receiver 双通道。

用户已确认修复后的 Poweramp 连续切歌取词实机通过。至此 Poweramp Provider 的本地歌词、
封面、翻译按钮、播放时钟与切歌链全部完成设备验收。

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播。
- 不把在线歌词搜索带进第一切片。
- 不 Hook 混淆的 `p000.o70` / `h70` 解析器。
- 不改 KuWo 封面路径，不把 `NativeLyricInfoPublisher` 的 copy-constructor 改成全局默认。
- 禁止延迟/`Handler.post` 的 PlaybackState 重放。同步 poke 仅限 PLAYING + 已有 lyricInfo + 每代一次。
