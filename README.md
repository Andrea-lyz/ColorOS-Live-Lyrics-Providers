# ColorOS Live Lyrics Providers

面向 ColorOS 锁屏歌词的独立 Root/LSPosed Provider 仓库。所有发布模块均输出标准
`MediaMetadata["lyricInfo"]`，可由 ColorOS SystemUI 直接消费；安装
`io.github.andrealtb.lockscreenlyrics` Bridge 后可获得通用逐字渲染、AOD、翻译按钮、
样式与兼容增强。

当前 v5 发布矩阵包含 14 个可安装 Provider：12 个播放器专属模块、通用播放器 Provider 与
Readify TTS Provider。所有模块均使用 libxposed API 102 入口、静态作用域与保护性异常策略；
通用 Provider 的设置 App 独立运行，模块逻辑只驻留在 `system_server`。

此外提供可选的独立应用[动态封面 Provider](#动态封面-provider可选)（`:artwork-provider-am`）：
它不属于歌词矩阵，单独安装、单独发布。

[English](README-English.md)

开发者入口：

- [Provider 适配技术指南](docs/4.0/PROVIDER-ADAPTATION-GUIDE.zh-CN.md)
- [Provider adaptation guide (English)](docs/4.0/PROVIDER-ADAPTATION-GUIDE.md)
- [播放器主动发布 `lyricInfo` 协议](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/4.1/docs/PLAYER_INTEGRATION.zh-CN.md)

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
| 通用播放器 | `:universal-provider` | `io.github.andrealtb.coloroslyrics.provider.universal` | `system`（在设置 App 中选择目标包） | ColorOS 16 / Android 16 |
| Readify AI | `:player-readify` | `io.github.andrealtb.coloroslyrics.provider.readify` | `com.readin.app` | `3.1.0` |

“适配基线版本”是静态逆向、实现和设备收口所使用的宿主样本，不表示 Provider 仅支持
该版本；宿主升级后如混淆结构或内部歌词链路发生变化，仍需重新验证。

Metrolist 与 Spotify 不提供翻译；其余模块按各播放器证据使用公开 action 或 Bridge
五槽按钮。QQ音乐 仅支持标准版，不包含 QQ音乐 HD。

## 通用播放器 Provider

通用播放器 Provider（`Provider-Universal 1.1.1 (3)`）面向没有专属适配、但会创建标准 Android
`MediaSession` 的播放器。它以静态 scope 仅运行在 `system_server`，从设置 App 同步用户明确选择的
目标包；没有被选择的音乐、视频和其他媒体 App 不会被观察、联网取词或写入 `lyricInfo`。

1.1.1 修复 ColorOS 17（Android 17）上的模块加载失败：C17 的 `MediaSessionRecord$SessionStub`
不再有合成字段 `this$0`，改为持有 `mRecord`（`WeakReference`），1.1.0 及更早版本按名查找
`this$0` 抛 `NoSuchFieldException`，模块虽已启用但对所有播放器零响应。1.1.1 按名逐级解析并
按字段类型兜底，ColorOS 16 及更早行为不变。

安装 APK 后打开 **Universal Player Provider**：在“播放器”页选择目标 App，在“来源”页调整歌词源
优先级，并按需打开逐字、翻译、原始歌词和脱敏诊断。模块会保留宿主已有 metadata，仅向选中播放器
当前活跃 MediaSession 附加标准 `MediaMetadata["lyricInfo"]`。它可单独供 ColorOS SystemUI 消费；
安装 Bridge 后，Bridge 仍只负责 SystemUI 的渲染、AOD 和翻译控制增强。

同一播放器不要同时使用专属 Provider 与通用 Provider。使用通用 Provider 时关闭播放器的蓝牙歌词、
车载歌词或其他会把当前歌词行覆写到媒体标题的功能；否则首曲匹配、缓存和切歌识别可能不可靠。反馈
问题时提供脱敏日志、目标包名和复现步骤，不要上传完整歌词、cookie、token 或私人媒体路径。

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
.\gradlew.bat :player-readify:testDebugUnitTest :player-readify:lintDebug :player-readify:assembleDebug
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

感谢 [Lyrico](https://github.com/Replica0110/Lyrico) 与
[Lyrico-Plugins](https://github.com/Replica0110/Lyrico-Plugins) 在本地音乐元数据、歌词管理和
插件化歌词源方面提供的开源工作与启发。

## Readify TTS Provider

`:player-readify`（`io.github.andrealtb.coloroslyrics.provider.readify`）是面向 Readify
3.1.0 的 API 102 TTS 适配，需要配套 Bridge 的 `sentence-window-v1` 支持。它已纳入
14 项正式 v5 发布矩阵与 Provider bundle。

协议、宿主范围与当前设备验证限制见
[player-readify/README.md](player-readify/README.md)。

## 动态封面 Provider（可选）

`:artwork-provider-am`（`io.github.andrealtb.artwork.am`）是可选、独立安装的应用，为锁屏提供
方形动态封面：按歌曲信息匹配 Apple Music 公开目录中的方形视频，下载并校验后通过
`artwork-contract` v1 交给已授权的 Bridge 渲染。它不是 Xposed 模块，不注入任何播放器，
也不属于上面的 v5 歌词矩阵；不安装它时锁屏保持官方静态封面。

- 首次使用：在应用内打开来源（默认关闭，默认仅非计费网络），再到 Bridge 的
  「设置 → 动态封面」中选择并授权它。
- 与歌词 Provider 完全独立：可单独安装、更新、卸载，歌词链路不受影响。
- 版本 1.0.0（versionCode 4），使用与歌词 Provider 相同的发布签名；
  正式发布时作为独立资产提供，不进入 Provider bundle。
- 开发入口与来源链路见 [artwork-provider-am/README.md](artwork-provider-am/README.md)。

`artwork-contract/` 是 Bridge 仓库同名协议模块的镜像，`src/` 必须与其逐字节一致；
`scripts/verify-artwork-contract.ps1` 在同时具备两个仓库检出时校验（Gradle 任务
`verifyArtworkContract`）。
