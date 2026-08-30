# ColorOS Live Lyrics Providers

面向 ColorOS 锁屏歌词的独立 Root/LSPosed Provider 仓库。所有发布模块均输出标准
`MediaMetadata["lyricInfo"]`，可由 ColorOS SystemUI 直接消费；安装
`io.github.andrealtb.lockscreenlyrics` Bridge 后可获得通用逐字渲染、AOD、翻译按钮、
样式与兼容增强。

当前 v5 适配矩阵已全部完成并通过对应设备门禁。

[English](README-English.md)

开发者入口：

- [Provider 适配技术指南](docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md)
- [Provider adaptation guide (English)](docs/4.0/PROVIDER-ADAPTATION-GUIDE.md)
- [播放器主动发布 `lyricInfo` 协议](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/4.0/docs/PLAYER_INTEGRATION.zh-CN.md)

## v5 Provider 矩阵

| 播放器 | Gradle module | applicationId | 宿主包 | 适配基线版本 |
|---|---|---|---|---|
| Salt Player(椒盐音乐) | `:player-salt` | `io.github.andrealtb.coloroslyrics.provider.salt` | `com.salt.music` | `12.3.0-alpha03` |
| 光锥音乐(含Play商店版) | `:player-cone` | `io.github.andrealtb.coloroslyrics.provider.cone` | `ink.trantor.coneplayer` / `ink.trantor.coneplayer.gp` | 正式版 `v1.2.0(c77a1ea49)`；GP 共用适配轮廓 |
| 酷我音乐 | `:kuwo-music` | `io.github.andrealtb.coloroslyrics.provider.kuwo` | `cn.kuwo.player` | `12.2.0.0` |
| 落雪音乐(含Walnut fork) | `:player-lx` | `io.github.andrealtb.coloroslyrics.provider.lx` | `cn.toside.music.mobile` / `com.lxwalnut.music.mobile` | LX `1.8.4`；Walnut `26.07.16` |
| Poweramp | `:player-poweramp` | `io.github.andrealtb.coloroslyrics.provider.poweramp` | `com.maxmpz.audioplayer` | `build-1025-bundle-play` |
| Metrolist | `:player-metrolist` | `io.github.andrealtb.coloroslyrics.provider.metrolist` | `com.metrolist.music` | `13.6.1` |
| 酷狗音乐（含概念版） | `:player-kugou` | `io.github.andrealtb.coloroslyrics.provider.kugou` | `com.kugou.android` / `com.kugou.android.lite` | 标准版 `20.8.0`；概念版 `5.2.61` |
| QQ音乐 | `:player-qq` | `io.github.andrealtb.coloroslyrics.provider.qq` | `com.tencent.qqmusic` | `20.7.5.8` |
| 网易云音乐(含荣耀版\9.0.40精简版) | `:player-netease` | `io.github.andrealtb.coloroslyrics.provider.netease` | `com.netease.cloudmusic` / `com.hihonor.cloudmusic` | 官方版 `9.5.70`；荣耀版 `3.5.20`；精简修改版 `9.0.40` |
| Apple Music | `:player-apple` | `io.github.andrealtb.coloroslyrics.provider.apple` | `com.apple.android.music` | `6.5.2` |
| Spotify | `:player-spotify` | `io.github.andrealtb.coloroslyrics.provider.spotify` | `com.spotify.music` | `9.1.78.2208` |
| 汽水音乐 | `:player-qishui` | `io.github.andrealtb.coloroslyrics.provider.qishui` | `com.luna.music` | `20.7.0` |

“适配基线版本”是静态逆向、实现和设备收口所使用的宿主样本，不表示 Provider 仅支持
该版本；宿主升级后如混淆结构或内部歌词链路发生变化，仍需重新验证。

Metrolist 与 Spotify 不提供翻译；其余模块按各播放器证据使用公开 action 或 Bridge
五槽按钮。QQ音乐 仅支持标准版，不包含 QQ音乐 HD。

## 架构

- `provider-core`：TrackIdentity、generation、标准 `lyricInfo` publisher、debug 与诊断。
- `reflection-core`：受控反射/DexKit 发现。
- `parser-lrc/qrc/yrc/krc/ttml`：中立歌词解析。
- `share:extensions-kt`、`share:extensions-android`、`share:lrckit`、
  `share:yrckit`：KuWo/NetEase 仍在使用的兼容 helper，不是可安装模块。

`io.github.proify.lyricon.lyric:model` 目前仅作为 KuWo/NetEase 兼容 DTO 依赖；

本仓库不分发词幕 Provider。需要词幕时请从
[LyricProvider 原项目](https://github.com/tomakino/LyricProvider) 获取，并将词幕显示或
产品链路问题反馈到原项目；Bridge 与本仓库只受理 ColorOS 原生 `lyricInfo` 链路问题。

## 新增播放器适配

不要从现有 Hooker 复制一份巨型实现后直接加入矩阵。先按
[Provider 适配技术指南](docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md) 确认：

1. 官方 payload 追加或自行构造；
2. 权威进程/MediaSession、曲目身份和 generation；
3. 歌词 lane、封面、PlaybackState 与翻译 action 所有权；
4. debug/隐私、单元测试和真机验收梯子；
5. `release/v5-provider-matrix.json` 的显式发布契约。


## 构建

要求 JDK 21 和 Android SDK：

```powershell
.\gradlew.bat assembleV5MatrixDebug
.\gradlew.bat assembleV5MatrixRelease
```

单模块示例：

```powershell
.\gradlew.bat :player-qishui:assembleDebug
```

Release 构建使用 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、
`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 环境变量。

## 文档与来源

- Provider 适配主线（中文）：`docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md`
- Provider adaptation guide：`docs/4.0/PROVIDER-ADAPTATION-GUIDE.md`
- 迁移状态与边界：`docs/4.0/PHASE-0-MIGRATION-MAP.md`
- 各播放器设备收口：`docs/4.0/PHASE-4-*-MIGRATION-REPORT.md`
- 仓库清理与最终构建：`docs/4.0/REPOSITORY-CLEANUP-REPORT.md`
- 旧 LyricProvider 来源、基线与署名：`NOTICE`

许可证为 Apache-2.0。保留的第三方来源和历史贡献者署名见源码头、`NOTICE` 与迁移报告。

## 致谢

特别感谢 [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider) 原项目及其
贡献者，为早期播放器适配、逆向思路和代码基线提供了重要参考。尽管本仓库围绕标准
v5 `lyricInfo`、Root/LSPosed 架构及各播放器内部歌词链路进行了近乎从头到尾的全面
重构，这段演进仍离不开原项目及社区贡献者的探索与积累。
