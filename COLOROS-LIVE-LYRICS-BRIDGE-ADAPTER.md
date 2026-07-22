# ColorOS Live Lyrics Bridge 适配指南

本文档记录 QQ 音乐和网易云音乐适配后的工程经验，供后续 LyricProvider fork 新增播放器模块时参考。

目标不是让 LyricProvider 直接绘制锁屏歌词，而是在已有 provider 模块拿到播放器内部歌词后，把同一份 `Song` 转换为 ColorOS Live Lyrics Bridge 能消费的外部歌词广播。Bridge 侧再负责覆盖 SystemUI 官方 `lyricInfo`、解析逐字歌词、处理锁屏区域绘制和过渡动画。

## 总体架构

每个播放器保持独立 APK / LSPosed 模块：

- QQ 音乐用户安装 `qq-music` provider。
- 网易云音乐用户安装 `163-music` provider。
- 后续 Apple Music、Poweramp、酷狗等也应各自保持独立模块。

模块内推荐流程：

1. 在目标播放器进程 hook 到歌曲切换、播放状态和歌词下载/缓存结果。
2. 组装 LyricProvider 标准 `Song`。
3. 先调用 `lyricProvider.player.setSong(song)`，保持词幕原本能力。
4. 再调用 `SaltLyricBridge.send(appContext, song)`，把同一份歌词发给 ColorOS Live Lyrics Bridge。

不要在 provider 里直接修改 SystemUI、MediaSession artwork、通知封面或锁屏 UI。provider 只发送歌词数据，所有锁屏显示行为都留给 Bridge 侧。

## 已验证落点

### QQ 音乐

参考：

- `qq-music/src/main/kotlin/io/github/proify/lyricon/qmprovider/xposed/QQMusic.kt`
- `qq-music/src/main/kotlin/io/github/proify/lyricon/qmprovider/xposed/SaltLyricBridge.kt`

当前路线：

1. hook `android.media.session.MediaSession.setMetadata(MediaMetadata)` 识别切歌，并缓存媒体信息。
2. 通过 QQ 内部歌词下载结果转换为 `Song`。
3. 在 `updateLyriconSong(song)` 中同时调用：

```kotlin
lyriconProvider?.player?.setSong(song)
SaltLyricBridge.send(appContext, song)
```

QQ 音乐的重点是拿内部歌词对象，不依赖系统官方 `lyricInfo`。官方 `lyricInfo` 通常只有简陋行歌词，不能满足逐字歌词和翻译需求，Bridge 收到 provider 广播后会覆盖官方渲染。

### 网易云音乐 / 荣耀版

参考：

- `163-music/src/main/kotlin/io/github/proify/lyricon/cmprovider/xposed/CloudMusic.kt`
- `163-music/src/main/kotlin/io/github/proify/lyricon/cmprovider/xposed/SaltLyricBridge.kt`

当前路线：

1. hook `android.media.session.MediaSession.setMetadata(MediaMetadata)` 识别切歌。
2. 用 DexKit / Tinker classloader 重挂能力维持网易云版本兼容。
3. 优先读取本地歌词缓存，缺失时走网易云内部下载结果。
4. 缓存数据转换为 `Song` 后，在 `setSong(song)` 中同时调用：

```kotlin
lyricProvider?.player?.setSong(song)
SaltLyricBridge.send(appContext, song)
```

网易云会遇到只有 LRC、没有 YRC 的歌曲。这种情况下允许回退为行歌词，不要为了制造逐字动画强行合并相邻歌词行；实践证明会导致更多行错位。

### Poweramp

参考：

- `poweramp-music/src/main/kotlin/io/github/proify/lyricon/paprovider/xposed/PowerAmp.kt`
- `poweramp-music/src/main/kotlin/io/github/proify/lyricon/paprovider/xposed/PowerampSaltLyricBridge.kt`
- `poweramp-music/src/main/kotlin/io/github/proify/lyricon/paprovider/xposed/PowerampLyricInfoPublisher.kt`

当前路线：

1. 监听 Poweramp 的 `com.maxmpz.audioplayer.TRACK_CHANGED` 广播识别本地歌曲切换。
2. 从广播 extras 缓存 `id`、标题、歌手、专辑、时长和路径。
3. 将 Poweramp 路径转换为 SAF URI 后，用 TagLib 读取音频标签里的 `LYRICS` 字段。
4. 本地歌词缺失时，按模块设置走在线歌词搜索。
5. 曲目切换时先发送带 generation 的 `trackChanged`；歌词准备完成后发送 `lyricReady`，同时保留向 Poweramp `MediaSession` 注入 `lyricInfo` 的兼容路线。
6. `MediaSession#setPlaybackState` 继续交给原版词幕 Provider；Bridge 直接使用 Poweramp 原生 MediaSession 状态，不再重复发送外部播放状态。
7. 每次歌词就绪时只生成一份歌词 JSON、逐行 LRC 和 enhanced LRC，由外部广播和 metadata 注入共同复用。
8. 本地标签若使用 `[行时间]单词 [单词时间]...` 的方括号逐字格式，只在 Bridge payload 中转换为标准 `<单词时间>` enhanced LRC；官方逐行 `lyric` 必须移除行内时间戳，原版词幕收到的 `Song` 保持不变。

```kotlin
provider?.player?.setSong(song)
PowerampLyricInfoPublisher.onLyricReady(appContext, song, generation)
```

Poweramp 的重点是保留本地播放器体验：优先使用内嵌/本地歌词，只有用户启用联网搜索时才走在线匹配。Bridge 适配不得改变原版词幕的 `setSong` / `setPlaybackState` 路线，也不要在原生 MediaSession 之外再广播一份播放进度；重复状态源会让一次拖动进度触发多轮 Recycler 定位。内部反射 probe 只用于显式 debug，发布态不应安装这些诊断 hook。Bridge 侧只接收歌词，不参与文件读取、SAF 授权和封面链路。

### Spotify

参考：

- `spotify-music/src/main/kotlin/io/github/proify/lyricon/spotifyprovider/xposed/Spotify.kt`
- `spotify-music/src/main/kotlin/io/github/proify/lyricon/spotifyprovider/xposed/SaltLyricBridge.kt`

当前路线：

1. hook `android.media.session.MediaSession.setMetadata(MediaMetadata)` 识别 Spotify 曲目 id。
2. hook OkHttp headers，复用 Spotify 内部请求所需鉴权信息。
3. 通过 Spotify API 获取标准同步歌词，并转换为 `Song`。
4. 在 `setSong(song)` 中同时调用：

```kotlin
lyriconProvider?.player?.setSong(song)
SaltLyricBridge.send(appContext, song)
```

Spotify 当前只按标准行歌词适配。`transliteratedWords` 更接近音译/罗马音，不应当发送到 Bridge 的 `translationLyric`，避免锁屏翻译位被音译污染。

## Bridge Direct v4 协议

Provider 注入到播放器进程后，直接向 SystemUI 发送显式 v4 广播；不经过 Bridge
应用的 ContentProvider，也不依赖 Bridge 被系统启动。

```kotlin
val payloadIntent = Intent().apply {
    putExtra("source", "lyricprovider/<module-id>")
    putExtra("requestId", requestId)
    putExtra("mediaId", song.id.orEmpty())
    putExtra("trackKey", trackKey)
    putExtra("songName", song.name.orEmpty())
    putExtra("artist", song.artist.orEmpty())
    putExtra("duration", song.duration)
    putExtra("lyric", plainLrc)
    putExtra("rawLyric", enhancedLrc)
    putExtra("translationLyric", translationLrc)
    putExtra("capturedAt", System.currentTimeMillis())
}
SystemUiBroadcastSender.submit(context, payloadIntent, TAG, "lyricprovider/<module-id>")
```

共享 sender 会把 action 设为 `EXTERNAL_LYRIC_DIRECT_V4`、目标包设为
`com.android.systemui`，并写入 `protocolVersion`、`source`、`playerPackage`、
`senderPackage` 和 `senderKind`。SystemUI 仅按静态 `source -> playerPackage`
白名单接收；ColorOS 无法可靠提供广播发送 UID，因此这不是签名或 UID 身份认证。

字段约定：

| 字段 | 说明 |
|:--|:--|
| `source` | 数据来源，建议使用 `lyricprovider/<module-id>`，例如 `lyricprovider/qq-music`。 |
| `requestId` | 歌词请求唯一键，应包含媒体 id 和歌词内容 hash。歌词内容变化时必须变化。 |
| `mediaId` | 播放器内部歌曲 id。没有稳定 id 时可留空，但要保证 `trackKey` 可用。 |
| `trackKey` | 标准化后的 `title|artist`，用于本地歌曲、在线匹配歌曲和媒体 id 不稳定场景。 |
| `songName` / `artist` | 当前歌曲标题和歌手。 |
| `duration` | 歌曲时长，单位毫秒。 |
| `lyric` | 普通 LRC，作为行级兜底。 |
| `rawLyric` | 优先字段。应尽量提供带 `<mm:ss.xxx>` 的 enhanced LRC / karaoke LRC。 |
| `translationLyric` | 翻译 LRC，时间戳应与主歌词行对齐。 |
| `capturedAt` | 提交时间戳，便于 SystemUI 丢弃旧 payload。 |

不要在 direct payload 里携带封面，也不要在 provider 里改 `MediaMetadata` 的 artwork。封面收发应完全沿用播放器和 SystemUI 原链路，避免纯色封面、短暂闪现后被覆盖等问题。

所有 direct payload 在提交前必须通过共享 `SystemUiBroadcastSender` 使用
`Parcel.dataSize()` 预检。当前保守上限为 512 KiB：逐字歌词超限时先把
`rawLyric` 降级为行级 `lyric`，保留 `translationLyric`；降级后仍超限则拒绝发送，
并按来源节流记录警告，避免把超大事务交给 Binder。

## 歌词转换规则

`rawLyric` 是最重要的字段。推荐转换为：

```lrc
[00:10.000]<00:10.000>第<00:10.300>一<00:10.600>句<00:10.900>
[00:13.000]<00:13.000>Hello <00:13.400>world<00:13.900>
```

规则：

- 行时间、字词时间统一使用毫秒精度，格式化为 `mm:ss.xxx`。
- `RichLyricLine.words` 存在时优先使用真实逐字/逐词时间。
- 有些播放器的 word time 是行内相对时间，有些是歌曲绝对时间；需要按模块实际数据归一化。
- 没有 word 数据时，普通 LRC 行可作为兜底，不要伪造高风险逐字。
- 行结束时间可用 `line.duration`、`line.end - line.begin` 或最后一个 word time 推断。
- 翻译只放入 `translationLyric`，不要混入 `rawLyric`。
- 罗马音不要当翻译。除非 Bridge 侧新增独立字段，否则不要把 romaji/roma 写进 `translationLyric`。

`lyric` 用普通 LRC：

```lrc
[00:10.000]第一句
[00:13.000]Hello world
```

`translationLyric` 用同时间戳 LRC：

```lrc
[00:10.000]Translated first line
[00:13.000]你好，世界
```

## 过滤和清洗

provider 发给 Bridge 前应做轻量清洗：

- 删除空行。
- 保留所有带时间轴的开头制作信息、作词、作曲、出品、OP、SP 等歌词行；显示级清理由 Bridge / SystemUI 根据用户配置统一处理。
- 删除翻译占位符，例如 `//`。
- 保持原始歌词分行，不要把相邻行合并成一行。
- 不要为了修某首歌重写全局编码逻辑。缓存文件编码必须按来源明确判断，避免把 UTF-8 / GB18030 互相误解码。
- 纯音乐或无有效歌词时不要发送有效歌词 payload。

元数据过滤要保守。宁可漏掉少量制作信息，也不要把主歌词误删。

## Hook 点选择原则

优先级从高到低：

1. 播放器内部歌词对象或内部歌词缓存。
2. 播放器内部网络下载回调。
3. LyricProvider 已经解析出的 `Song`。
4. 系统 MediaSession / 通知官方 `lyricInfo`。

官方 `lyricInfo` 只能作为兜底观察源，不适合作为 QQ、网易云这类目标的最终数据源。我们的目标是覆盖官方 `lyricInfo`，尤其是拿到逐字歌词和翻译。

复杂播放器要注意多进程：

- QQ 音乐主要在 `com.tencent.qqmusic:QQPlayerService` 处理播放和歌词。
- 网易云音乐 Provider 只由已验证持有 MediaSession 的主进程
  `com.netease.cloudmusic` 注册；`:play`、`:push` 等进程不得成为第二事件源。
- Spotify Provider 只在 `com.spotify.music` 主进程注册。
- 异步歌词请求必须捕获 `mediaId + generation + MediaSession identity`，回调提交前再次
  与当前令牌精确比对；旧结果可以写入缓存，但不得调用 `setSong()`。
- 歌词提交顺序统一为 `setSong -> setPosition -> setPlaybackState -> lyricReady -> playbackState`。
- 使用热更新框架的 App，需要在 Tinker / split classloader 加载后重新挂钩。
- 类名、方法名不稳定时可以使用 DexKit 查找特征，但最终入口仍应落到稳定的歌曲/歌词数据流。

## 调试日志

推荐抓取：

```powershell
adb logcat -c
adb shell setprop log.tag.Lyricon_SaltBridge VERBOSE
adb shell setprop log.tag.Lyricon_NeteaseBridge VERBOSE
adb logcat -d -v time -s LockscreenLyrics AndroidRuntime Lyricon_SaltBridge Lyricon_NeteaseBridge > logcat.log
```

实时观察：

```powershell
adb logcat -v time -s LockscreenLyrics AndroidRuntime Lyricon_SaltBridge Lyricon_NeteaseBridge
```

Provider 的正常提交、缓存命中和 direct broadcast 成功日志默认静默，仅在对应 Tag 显式设为
`VERBOSE` 时输出；警告和错误始终保留。抓取结束后用 `setprop log.tag.<Tag> INFO`
恢复默认值，避免调试日志持续写入及被 LSPosed 重复镜像。

provider 侧应至少打印：

- source/module id
- media id
- 原始行数和发送行数
- `rawLyric` / `translationLyric` 字符数
- 第一句短文本

Bridge 侧重点看：

- 是否收到 `Accepted direct external lyric broadcast`
- 是否命中当前 `trackKey` / `mediaId`
- parser 使用的是 enhanced LRC、普通 LRC 还是官方 `lyricInfo`
- 是否丢弃了旧 request
- SystemUI 是否仍短暂显示官方歌词

## 验收矩阵

每个新 provider 至少测这些场景：

| 场景 | 期望 |
|:--|:--|
| 在线逐字歌词 | 锁屏显示逐字动画，且不是官方简陋 `lyricInfo`。 |
| 在线翻译歌词 | 主歌词和翻译行对齐，不把罗马音识别为翻译。 |
| 只有 LRC、无逐字 | 可回退行歌词，不应错行、合并行或把前三句带坏。 |
| 本地歌曲在线匹配歌词 | `trackKey` 能匹配当前播放歌曲，不被媒体 id 不稳定影响。 |
| 快速切歌 / 连续快进 | 旧歌词不会覆盖新歌，逐字渲染能恢复。 |
| 纯音乐 | 不发送假歌词，不残留上一首歌词。 |
| 封面 | provider 不影响封面，锁屏封面不闪纯色、不被模块覆盖。 |
| App 内翻译/罗马音开关 | provider 和 Lyricon 原功能保持一致，Bridge 不误用 roma。 |

## 常见坑

- 不要把 provider 做成一个“大一统模块”。QQ、网易云、Apple Music、Poweramp 等应独立发布，用户按播放器安装对应 provider。
- 不要依赖外部网络匹配补翻译。大厂播放器内部对象通常更准，尤其是 QQ / 网易云。
- 不要把官方 `lyricInfo` 当成功适配。看到官方歌词不代表 provider 广播生效。
- 不要 hook 后重写 MediaSession metadata。歌词广播和封面链路必须解耦。
- 不要为了单首歌的 LRC 时间异常做激进全局补偿。优先接受行级兜底。
- 不要在 provider 侧实现锁屏过渡动画。切歌遮罩、官方歌词闪现处理属于 Bridge / SystemUI hook 侧。
- 不要在未确认来源格式时做编码猜测。歌词乱码修复应只作用于明确来源。

## 新模块接入清单

1. 确认目标播放器 package / process。
2. 找到稳定切歌点，通常是 `MediaSession.setMetadata`。
3. 找到内部歌词数据流，优先缓存或下载回调。
4. 转换为 `Song`，保留 id、title、artist、duration、lyrics、translation、words。
5. 添加模块自己的 `SaltLyricBridge.kt`，设置唯一 `source` 和 `requestId` 前缀。
6. 在最终 `setSong(song)` 位置追加 `SaltLyricBridge.send(appContext, song)`。
7. 更新模块简介，说明“为词幕和 ColorOS Live Lyrics Bridge 提供歌词”。
8. 构建 debug APK 并安装测试。
9. 用 logcat 验证 Bridge 收到 provider 广播，并确认锁屏不是官方 `lyricInfo` 回落。
10. 覆盖验收矩阵后再发 release。

## 构建命令

单模块 debug 构建：

```powershell
.\gradlew.bat :<module-id>:assembleDebug
```

示例：

```powershell
.\gradlew.bat :qq-music:assembleDebug
.\gradlew.bat :163-music:assembleDebug
```

导出的 APK 通常在：

```text
build/all-apks/debug/
```

如果 release 侧需要和 ColorOS Live Lyrics Bridge 保持同签名，确保对应模块的 `signingConfigs.release` 可用；本地 debug 验证可以保留 release keystore 不存在时回退 debug signing 的逻辑。
