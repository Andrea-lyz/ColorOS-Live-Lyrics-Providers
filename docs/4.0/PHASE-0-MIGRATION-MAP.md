# 4.0 Phase 0 Provider 迁移映射

记录日期：2026-08-24  
来源仓库：`LyricProvider` `master`，commit `292a7da3f88a87e8c6df6b4ae4f56455b6856c72`  
当前工作分支：`4.0`  
状态：仅完成盘点和映射；以下新包名、模块名和 v5 边界尚未应用到源码。

## 1. 新 applicationId / namespace 规则

目标格式统一为：

```text
io.github.andrealtb.coloroslyrics.provider.<player>
```

namespace、applicationId 和 Kotlin/Java 根包在迁移后保持同一 `<player>` 后缀。`163-music` 使用 `netease`，因为 Android applicationId 的 segment 不能以数字开头；`qq-music-hd` 使用 `qqhd`，`music-free` 使用 `musicfree`。

## 2. 现有 application module 映射

| 旧 module | 旧 applicationId / namespace | 旧版本 | 旧 Xposed scope（原始值） | 4.0 目标 module | 4.0 目标 applicationId / namespace |
| --- | --- | --- | --- | --- | --- |
| `163-music` | `io.github.proify.lyricon.cmprovider` | `17 / 1.1.7` | `com.netease.cloudmusic`, `com.hihonor.cloudmusic`, `$syllable`, `$translation` | `player-netease` | `io.github.andrealtb.coloroslyrics.provider.netease` |
| `apple-music` | `io.github.proify.lyricon.amprovider` | `12 / 1.1.4` | `com.apple.android.music`, `$syllable`, `$translation` | `player-apple` | `io.github.andrealtb.coloroslyrics.provider.apple` |
| `qq-music` | `io.github.proify.lyricon.qmprovider` | `11 / 1.1.6` | `com.tencent.qqmusic`, `$syllable`, `$translation` | `player-qq` | `io.github.andrealtb.coloroslyrics.provider.qq` |
| `qq-music-hd` | `io.github.proify.lyricon.qmhdprovider` | `1 / 1.0.0` | `com.tencent.qqmusicpad`, `$syllable`, `$translation` | `player-qqhd` | `io.github.andrealtb.coloroslyrics.provider.qqhd` |
| `kugou-music` | `io.github.proify.lyricon.kgprovider` | `15 / 1.2.0` | `com.kugou.android`, `com.kugou.android.lite`, `$syllable`, `$translation` | `player-kugou` | `io.github.andrealtb.coloroslyrics.provider.kugou` |
| `kuwo-music` | `io.github.proify.lyricon.kwprovider` | `12 / 2.1.0` | `cn.kuwo.player`, `车载歌词` | `player-kuwo` | `io.github.andrealtb.coloroslyrics.provider.kuwo` |
| `spotify-music` | `io.github.proify.lyricon.spotifyprovider` | `12 / 1.1.6` | `com.spotify.music` | `player-spotify` | `io.github.andrealtb.coloroslyrics.provider.spotify` |
| `lx-music` | `io.github.proify.lyricon.lxprovider` | `5 / 1.0.3` | `cn.toside.music.mobile`, `com.lxwalnut.music.mobile`, `$translation` | `player-lx` | `io.github.andrealtb.coloroslyrics.provider.lx` |
| `poweramp-music` | `io.github.proify.lyricon.paprovider` | `11 / 1.0.14` | `com.maxmpz.audioplayer`, `$syllable`, `$translation` | `player-poweramp` | `io.github.andrealtb.coloroslyrics.provider.poweramp` |
| `salt-player-music` | `io.github.proify.lyricon.saltprovider` | `4 / 1.0.3` | `com.salt.music` | `player-salt` | `io.github.andrealtb.coloroslyrics.provider.salt` |
| `qishui-music` | `io.github.proify.lyricon.qishuiprovider` | `10 / 1.2.7` | `com.luna.music`, `$syllable`, `$translation` | `player-qishui` | `io.github.andrealtb.coloroslyrics.provider.qishui` |
| `music-free` | `io.github.proify.lyricon.musicfreeprovider` | `4 / 1.0.0` | `fun.upup.musicfree`, `桌面歌词` | `player-musicfree` | `io.github.andrealtb.coloroslyrics.provider.musicfree` |
| `gramophone` | `io.github.proify.lyricon.gramophoneprovider` | `1 / 1.0.0` | `org.akanework.gramophone` | `player-gramophone` | `io.github.andrealtb.coloroslyrics.provider.gramophone` |
| `symfonium` | `io.github.proify.lyricon.symfoniumprovider` | `1 / 1.0.0` | `app.symfonik.music.player`, `$syllable`, `$translation` | `player-symfonium` | `io.github.andrealtb.coloroslyrics.provider.symfonium` |
| `metrolist-music` | `io.github.proify.lyricon.metrolistprovider` | `1 / 1.0.0` | `com.metrolist.music`, `lyrics`, `metrolist`, `youtube-music` | `player-metrolist` | `io.github.andrealtb.coloroslyrics.provider.metrolist` |

## 3. 纯词幕交付物

这些模块没有对应的 4.0 v5 player Provider：

| 旧 module | 处理 |
| --- | --- |
| `cloud-provider` | 删除；在线搜索/通用词幕交付不再由 4.0 Provider 套件承载 |
| `meizu-provider` | 删除；用户需要词幕功能时使用词幕官方 Provider |
| `car-provider` | 删除；车载词幕功能不再作为独立词幕 Provider 交付 |
| `share:meizu-provider` | 删除，除非后续证明有 v5 原生 metadata 代码可迁入中立 core |
| `share:car-provider` | 删除，除非后续证明有 v5 原生 metadata 代码可迁入中立 core |

删除前必须完成引用盘点、许可证/NOTICE 保留和新旧 APK 并存说明；Phase 0 不执行删除。

## 4. 旧 module → 4.0 module 候选映射

| 旧 module | 4.0 候选 | 说明 |
| --- | --- | --- |
| `share:extensions-kt` | `provider-core` / `reflection-core` | 只迁移与 v5 仍相关的中立 Kotlin 扩展 |
| `share:extensions-android` | `provider-core` | 只迁移 MediaSession、metadata、日志和 Android 生命周期所需代码 |
| `share:lrckit` | `parser-lrc` | 保留解析正确性测试，去除词幕交付依赖 |
| `share:qrckit` | `parser-qrc` | 保留 QRC 时间轴和翻译一对一对齐测试 |
| `share:yrckit` | `parser-yrc` | 保留 YRC 逐字时间处理 |
| `share:krckit` | `parser-krc` | 保留 KRC 解析和时间分类 |
| `share:cloudlyric` | `provider-core` 或中立下载模块 | 只在 v5 Provider 仍直接使用时迁入 |
| `apple-music` | `player-apple` | 单 Provider 直接发布原生 `lyricInfo` |
| `163-music` | `player-netease` | 主进程和 `:play` 进程共用 v5 publisher，保留偏好读取边界 |
| `qq-music` / `qq-music-hd` | `player-qq` / `player-qqhd` | 双包隔离，HD 在取证完成前不宣称支持 |
| `kugou-music` | `player-kugou` | 普通与 Lite 的 SystemUI 差异放在 player policy |
| `kuwo-music` | `player-kuwo` | 原生 `lyricInfo`、封面和同曲模型边界独立保留 |
| `spotify-music` | `player-spotify` | 网络/签名/响应上限迁入播放器 profile |
| `lx-music` | `player-lx` | LX / Walnut 作为一个 Provider APK，scope 仍按宿主隔离 |
| `poweramp-music` | `player-poweramp` | 本地来源优先级和 MediaSession metadata 迁入 |
| `salt-player-music` | `player-salt` | 从 Bridge 迁出 DexKit 发现和 CJK 协程兼容逻辑 |
| `qishui-music` | `player-qishui` | 先 root v5，再单独评估 NPatch；不修改 SystemUI 绕过宿主完整性 |
| `music-free` | `player-musicfree` | 重新确认内部 session 和歌词模型后迁移 |
| `gramophone` | `player-gramophone` | 本地歌词来源和 session 绑定迁移 |
| `symfonium` | `player-symfonium` | 歌词来源和 metadata 注入迁移 |
| `metrolist-music` | `player-metrolist` | generation、TTML 和缓存边界迁移 |

## 5. v4 → v5 数据边界映射

| 3.x/v4 入口 | 4.0 目标 | Phase 0 处理 |
| --- | --- | --- |
| `SystemUiBroadcastSender.submit` / `submitWithLyricLineFallback` | `NativeLyricInfoPublisher.publish` | 记录映射，未实现 |
| `EXTERNAL_LYRIC_DIRECT_V4` 私有广播 | 播放器自己的 `MediaMetadata["lyricInfo"]` | 记录为最终唯一数据交界，未删除旧入口 |
| `LyriconFactory.createProvider` | `RuntimeModeResolver` + player hook + v5 publisher | 结构目标，未改源码 |
| `LyriconProvider.player.setSong` | track identity/generation + native metadata publish | 不再制造词幕 Song；未改源码 |
| `setPlaybackState` / `setPosition` 词幕提交 | 使用宿主 MediaSession 的播放状态和位置 | 不把旧词幕状态调用直接搬进 v5 |
| `source` / `senderKind` / Provider applicationId 白名单 | player profile、宿主 scope 和运行模式诊断 | 不迁入 Bridge；未改旧 Bridge |
| `external-lyric-protocol` fixture/Parcel | provider 内部的有界 payload 与完整 metadata fail-open | 后续 Phase 1/5 再定义和删除 |

v5 具体契约仍以 `todo.md` 第 3 节为准：只写播放器自己的 `lyricInfo`，保留其它 metadata，区分 WORD/LINE/UNTIMED/INVALID，并对 display/raw/translation 做有界单调对齐。

## 6. 4.0 新仓库边界

- 新仓库从旧 `LyricProvider` 的 tracked source 建立，不带旧仓库的 remote；旧仓库不作为新仓库的工作树继续修改。
- Phase 0 不改旧包名、不改旧 namespace、不删除词幕依赖、不切换 v4 transport。
- Phase 1 才开始创建 `provider-core`、`reflection-core`、parser modules、`RuntimeModeResolver` 和 `NativeLyricInfoPublisher`。
- 每个 player Provider 完成 v5 和 root/NPatch 真机门禁后，才允许从旧 Bridge/旧 Provider 删除对应 fallback。

