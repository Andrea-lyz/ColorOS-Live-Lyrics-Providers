# 4.0 Phase 4 酷狗迁移报告

记录日期：2026-08-27  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-kugou`  
逆向依据：`PlayerSource/Kugou/KUGOU-NATIVE-LYRICINFO-TRANSLATION-INVESTIGATION.md`  
方法模板：`docs/4.0/PLAYER-ADAPTATION-REFERENCE-SALT.md`、`:player-lx`、`:player-poweramp`、`:kuwo-music` 官方 encoder  
真机日志：`logs/lyrics-log-20260827-063251.txt`（标准版 + 概念版；用户确认通过）

## 交付边界

- 新建 `player-kugou`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.kugou`，`versionCode=1` /
  `versionName=1.0.0`。
- 一个 APK 覆盖两个宿主：`com.kugou.android` 与 `com.kugou.android.lite` 都只
  hook `:support` / `.support`（不 hook Lite 主进程 / `.message`）。长英文歌名
  不得按车机行改写成 `title=Swift`。不要改写宿主 `setPlaybackState`。
  翻译按钮仍走 Bridge 5 槽收藏覆盖，不注入公开 `ACTION_TOGGLE_TRANSLATION`。
- 官方 `lyricInfo` 经 `KuGouOfficialLyricInfoEncoder` 就地修补
  （`source=kugou-internal`）。不要改成 `NativeLyricInfoPublisher` / Bridge
  envelope，也不要发送 v4 广播或挂载词幕。
- 保留官方 `id` / `songId` / `lyricType` / `lyric` / `noLyric`；补
  `rawLyric`、`translationLyric`、`songName`、`artist`、`sessionGeneration`。
  Type 0 罗马音不得进入 `translationLyric`。
- 不注入公开 `ACTION_TOGGLE_TRANSLATION`；翻译按钮仍走 5 槽收藏覆盖。
- 空 typed `MediaMetadata.Builder()` 全量拷贝（禁止 `Builder(existing)`）。
  HARDWARE 或边长 >240px 的 bitmap 用 Canvas 重绘为 software ARGB_8888。
  不 HTTP 拉封面、不 snapshot、不发明封面。只叠加主 session tag
  `KGMediaSession`。
- 调试开关复用 `provider-core`：`ProviderId.KUGOU`、prefs
  `kugou_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:kugou-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/kugou-music` / `lyricprovider/kugou-concept-music`）。
  4.0 不适配 NPatch。旧 `LyricProvider` 仓库的 `:kugou-music` 仍留给
  3.x 发布，不是本切片编辑目标。
- 标准版 20.6.x 仍不支持（缺少 `getWords()`）。调查样本为标准版 20.8.0 与
  概念版 5.2.61。

## 运行时数据流

```text
TRACK_OBSERVED (MediaSession#setMetadata，身份已消毒)
        ↓ generation++
LyricManager#(String, boolean) afterHook
        ↓ LyricData getters / Lite z,o,p,v,w,t,u / 字段 f,d,e,i,j,k,l
        ↓ 否则读 KRC/LRC 文件（parser-krc，仅 type=1 翻译）
        ↓ foreign-file / leading-metadata 拒绝
        ↓ LyricsCache
NATIVE_METADATA_INTERCEPTED (KGMediaSession setMetadata)
        ↓ 空 Builder 拷贝 + 修补 lyricInfo
        ↓ 歌词晚到时每代最多 replay 一次
NATIVE_LYRICINFO_PATCHED
```

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.kugou`，`supportsTranslation=true`。
- `com.kugou.android` 与 `com.kugou.android.lite` 留在
  `BRIDGE_PLAYER_PACKAGES` 和
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为酷狗新增 v4 `EXTERNAL_SOURCES`。
- `shouldSuppressKuGouOfficialLyricInfo` 恒为 false：修补后的原生 payload
  就是权威歌词。

## 真机收口

- `lyrics-log-20260827-063251.txt`（用户确认全部通过）：标准版
  `com.kugou.android.support` 与概念版 `com.kugou.android.lite.support` 均
  `PROCESS_READY` / `NATIVE_LYRICINFO_PATCHED`；SystemUI `WORD_TIMED` 且
  `translationChars>0`；Rule0 收藏位有 heart/`MediaAction`（公开翻译 Action
  未注入）。日志无 `PLAYBACK_STATE_PROMOTED`。
- 此前伪造 `setPlaybackState`→`PLAYING`（含 `position=-1` 填 `0`）会让
  ColorOS 切歌窗口强制 `updateState`，锁屏岛概率不出现
  （`lyrics-log-20260827-062221.txt`、`lyrics-log-20260827-062335.txt`），
  已整段撤掉。
- 概念版长英文歌名不得当车机行（`lyrics-log-20260827-053644.txt`）；Lite
  不得 hook 主进程（`lyrics-log-20260827-054900.txt`）。

## 自动验证

```text
scripts\dev.cmd provider :player-kugou:testDebugUnitTest :player-kugou:assembleDebug
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
PROCESS_READY（标准版 / 概念版 :support 或 .support）
        ↓
TRACK_BOUND
        ↓
KUGOU_KRC_LOAD_CAPTURED / KUGOU_LYRIC_PARSED
        ↓
LYRIC_INFO_PATCHED / NATIVE_METADATA_INTERCEPTED / NATIVE_LYRICINFO_PATCHED
        ↓
Accepted bridge lyricInfo / NATIVE_LYRIC_RECEIVED
        ↓
锁屏 / AOD 可见逐字；外语歌可切翻译；概念版车机模式不得换代或改 TITLE/ARTIST
```

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播。
- 不改写宿主 `setPlaybackState`（切歌 `BUFFERING` 被 ColorOS 忽略是正确行为；伪造 `PLAYING` 会丢锁屏岛）。
- 不注入公开翻译 CustomAction，不覆盖桌面歌词槽。
- 不把罗马音写入 `translationLyric`。
- 不改 KuWo / LX / Poweramp / Metrolist 封面路径。
- 不在本切片改 Bridge 统一发布 workflow（仍从旧 `LyricProvider` 编
  `:kugou-music`）；4.0 正式发布时再切到本仓库 `:player-kugou`。
- 不为 20.6.x 补 `getWords()` 兼容。
