# ColorOS-Live-Lyrics-Providers 全量代码审查报告

> 审查日期：2026-08-28
> 范围：仓库全部 19 个 Gradle 模块（provider-core / reflection-core / 5 个 parser / 12 个 player / 4 个 share）主源、测试、构建脚本、Manifest、ProGuard、CI 与仓库卫生。
> 方式：`provider-core` 与 `reflection-core` 逐文件人工精读；其余模块分组深度审查并对关键结论（日志门禁、Apple DexKit 泄漏、Poweramp 广播导出、parser 测试执行、Git 状态、缺失 ProGuard 文件）做了独立复核（含 Gradle 实跑与编译产物探针）。
> 结论未修改任何源码。

## 总体判断

架构契约的骨架落实得相当扎实——`NativeLyricInfoPublisher` 的事务式发布 + Parcel fail-open、`TrackGenerationPolicy` 防串曲、`RuntimeModeResolver` fail-closed、无 NPatch/Bridge/词幕运行时残留、罗马音不入翻译通道、认证 token 不落日志——这些均经交叉核对无误。但存在**一个系统性隐私契约违反**、**一批"绕过核心设施自造轮子"导致的契约偏差与真实 bug**，以及**一个足以丢失全部工作成果的仓库状态问题**。

---

## P0 — 发布前必须修复

### P0-1. 常开 INFO 日志明文记录 title/artist/mediaId，在唯一支持的 ROOT_MODULE 下必然写入可导出的 LSPosed 持久日志（违反契约 §5.9，系统性）

- **命中模块**：spotify、apple、netease、qq、kugou、qishui、metrolist（poweramp、salt、cone、lx、kuwo 用 hash 或数字 id，合规，可作正确样板）。
- **典型位置**：
  - `player-spotify/.../SpotifyPlayerHooker.kt:347-370`（`SESSION_METADATA_OBSERVED`，每次 setMetadata，广告/播客也记）、`:471-483`
  - `player-metrolist/.../MetrolistPlayerHooker.kt:210-223`、`MetrolistLyricsFetcher.kt:99-104`
  - `player-apple/.../ApplePlayerHooker.kt:377-389`；netease `NeteaseLyricSessionCoordinator.kt:109-134`；qq `QqPlayerHooker.kt:293-300`；kugou `KuGouPlayerHooker.kt:266-272`；qishui `QishuiPlayerHooker.kt:291-300`
- **根因（已确认）**：`provider-core/.../StructuredDiagnostics.kt` 中只有 `logDebug` 检查 `isDebugEnabled`，`logInfo/logWarning` 无条件输出（刻意——启动摘要/关键状态本就常开）；且 `configureForRuntime` 在 `mode == ROOT_MODULE` 时**无论 debug 开关都会挂上 `XposedSink`**，故这些 INFO 事件始终同时进 logcat 和 LSPosed framework log。`SensitiveFieldRedactor.kt:11-17` 只脱敏 bearer/token/cookie/password/`/data/user`，**不处理曲目文本**。各模块把明文 `title=/artist=/mediaId=` 塞进 `message=` 字段，绕过了本该使用的 `DiagnosticEvent.trackHash`。
- **理由**：契约明文要求"title/artist/mediaId 只记 hash"，实测是用户完整收听历史（含网易/QQ songId、YouTube videoId、Spotify mediaId）以明文常态化写入可被 LSPosed 一键导出的日志包。按评级定义（隐私泄露 / 违反核心契约）属 P0。**缓解事实**：logcat 在 API27+ 非 root 应用不可读、内容即当前屏幕正在播放的曲目、导出需用户主动操作——并非远程可利用泄露，理性看也可判 P1；因其系统性且默认落持久日志，按 P0 处理。
- **修改方向**：修在**调用点**而非放开门禁。统一改用 `trackHash = DiagnosticHasher.sha256(track.buildStableKey())`（`NativeLyricInfoPublisher.kt:114` 已是正确示范），从 `message=` 移除原始 title/artist/mediaId；确需明文取证时降级为 `logDebug` 并加敏感开关。建议加架构测试：断言 INFO/WARN 事件的 `message`/`session` 不含 `getString(TITLE/ARTIST/MEDIA_ID)` 原值。

---

## P1 — 影响正确性/稳定性/隐私，应尽快修复

### P1-1. 仓库状态：整个 4.0 迁移完全未提交，HEAD 仍是旧布局，全部新模块 Kotlin 源码未被 Git 跟踪

- **已确认**：`git log` HEAD 为 `f0eeafa`（清理前）；`git status` 为 530 未暂存删除 + 43 修改 + 38 未跟踪；`git ls-files "player-apple/**/*.kt"` 与 `"kuwo-music/**/*.kt"` 均返回 **0**（`player-apple/` 整目录未跟踪）；被删除的 `163-music/ qq-music/ apple-music/…` 仍在索引中。
- **理由**：文档宣称的"12 模块 v5 矩阵完成"在版本库里毫无体现。全新 clone 得到的是引用已删除模块、无法构建的旧布局，且**丢失全部新 Provider 源码**。非代码 bug，但按"卫生死角"是当前最紧急的操作性风险——一次磁盘故障即抹掉数月工作。
- **修改方向**：确认 `.gitignore` 生效后分批提交迁移结果；提交后校验 `settings.gradle.kts` 模块列表与磁盘一致、能干净 clone 构建。

### P1-2. 多个"就地 overlay/追加"发布路径绕过 Parcel 超限 fail-open（契约 §3.1.7）

- **命中**：kuwo（`KuWoLyricInfoPublisher.kt:124-135`）、kugou（`KuGouMetadataCopy.kt:62-66` + `KuGouPlayerHooker.kt:188-195`）、qq（`QqPlayerHooker.kt:117-121,221-241`）、netease（`NeteasePlayerHooker.kt:140-146`）。
- **理由**：这些模块自造 overlay 只对封面位图降采样，对拼装后的 `lyricInfo` JSON（`rawLyric` 逐字增强 LRC 常为纯文本 2-3 倍 + `translationLyric`）**无字节测量**，且 before-hook 把放大后的 metadata 赋回**宿主自身的** `setMetadata` 入参——一旦超 Binder 事务上限，`TransactionTooLargeException` 在宿主帧内抛出。核心 `NativeLyricInfoPublisher` 为此建了 512KB/1.5M 闸门，qishui/apple/spotify/salt/cone/lx/metrolist/poweramp 都在用，唯这四条 append 路径漏用。真实概率被"位图≤240px"抵消，故 P1。
- **修改方向**：各 overlay 提交前做 `Parcel.dataSize()` 测量 + 字段字符上限，超限回退原始 metadata（不改 `args[0]`）；或把 core 的 Parcel-gate 抽成可复用于 append 语义的公共函数。

### P1-3. Apple 的 DexKitBridge 创建后从不关闭（宿主进程常驻原生内存泄漏）

- **已确认**：`player-apple/.../ApplePlayerHooker.kt:52,147,780` 创建并使用 `dexKitBridge`，全文件无 `.close()`/`onTerminate`。
- **理由**：`DexKitBridge` 是 `AutoCloseable`，持有整包解析后的 dex（Apple Music 体量可观）于原生堆，进程存活期永不释放。反差：Spotify 两个 resolver 正确用了 `reflection-core` 的 `DexKitBridge.withDexKit{ bridge.use{} }` 自动关闭。
- **修改方向**：改用 `withDexKit(sourceDir){…}` 在 mapper 类查找后即关闭，或 `installPlaybackItemHooks` 末尾显式 `dexKitBridge?.close()`；顺带复用其 `ensureNativeLibraryLoaded()` 替换手写 `System.loadLibrary`。

### P1-4. Poweramp `TRACK_CHANGED` 接收器以 `RECEIVER_EXPORTED` 注册 + sidecar 读取无大小上限

- **已确认**：`player-poweramp/.../PowerampPlayerHooker.kt:140` 为 `RECEIVER_EXPORTED`；`PowerampLyricLoader.kt:73-77` `readText()` 无上限、仅 `file.isFile` 挡目录。
- **理由**：广播来自 Poweramp 同 UID 进程，`RECEIVER_NOT_EXPORTED` 即可收到；EXPORTED 使任意第三方 App 可伪造 `path/title` extras 触发 `onTrackChanged`（`:152` 对来源零校验），让模块以 Poweramp 权限读取攻击者指定路径的 `.lrc`/内嵌标签并写进锁屏可见的 MediaSession，`readText()` 无上限还可指向巨型文件 OOM 宿主——DoS + 串词向量。
- **修改方向**：改 `RECEIVER_NOT_EXPORTED`；sidecar 读取加尺寸上限（与 `MAX_LYRIC_FIELD_CHARS` 对齐）。（是否跨进程发送该广播待真机复核，但 NOT_EXPORTED 覆盖同 UID 场景。）

### P1-5. Poweramp 本地 `.lrc` 无编码探测——GBK 乱码直发、UTF-16 静默失效、UTF-8 BOM 丢首行

- **位置**：`PowerampLyricLoader.kt:73-77`（`bufferedReader()` 默认 UTF-8，malformed 按 U+FFFD 替换不报错）；连带 `EnhanceLrcParser.kt:30`（`﻿` 非 whitespace，`trim()` 不去 BOM）。
- **理由**：中文本地 `.lrc` 大量为 GBK/GB18030，UTF-8 解码得到时间标签完好+正文全 `�`，`containsTimedLrc` 通过 → 乱码直接上锁屏；UTF-16 连时间标签都碎 → 静默无词；带 BOM 的 UTF-8 丢第一行。模块自述"编码探测"实际完全缺失。
- **修改方向**：读原始字节做轻量探测（BOM → 严格 UTF-8 试解码 `REPORT` → 回退 GB18030），解码后剥 `﻿`；纯函数，宜放 provider-core 并补测。

### P1-6. Poweramp 翻译 poke "每代最多一次"未真正成立 + 记账非原子

- **位置**：`PowerampPlayerHooker.kt:310-346`（inject 路径未检查 lyricInfo 是否已存在）、`:51-52`（`lastTranslationPokedGeneration` 为 `@Volatile` 但 check 与 set 分离）。
- **理由**：报告要求"仅 live=PLAYING 且**已有 lyricInfo** 时同步 poke 一次"，但 `PowerampTranslationActionPokePolicy.kt:31` 的 `hasLyricInfo` 守卫只约束了另一条路径。暂停→播放/冷启动时宿主先写 PLAYING（歌词尚未发布）→ inject 路径打 token 并耗掉本代预算 → 稍后 lyricInfo 发布时因 `generation==lastPoked` 被抑制 → ColorOS 整代不重绑、锁屏歌词缺失；并发下还能同代双 poke。
- **修改方向**：inject 打 token 前加 `lyricInfo` 非空判断；记账改 `AtomicLong.compareAndSet` 或统一进锁做 check-then-set。

### P1-7. Poweramp `drainPendingPublication` 先取出后可能静默丢词（metrolist 已修，poweramp 未同步）

- **位置**：`PowerampPlayerHooker.kt:510-530`（`:527` 先 `takeIfSame` 取空）→ `:421-422`（`handlePublication` 判 PENDING 时 `if(!allowPending) return`）。
- **理由**：drain 发生在 setPlaybackState/setActive before-hook，此刻 `controller.metadata` 可能仍是上一首或缺 bitmap → decide 判 PENDING 直接 return，或 publisher 判 STALE 未发布，两种结局 pending 都已取空，而 Poweramp 每曲仅一次 `TRACK_CHANGED` 无重取 → 整曲丢词。这正是 `PHASE-4-METROLIST-MIGRATION-REPORT` 记录并已在 metrolist 修复的同类事故（复制粘贴未同步修复的实证）。
- **修改方向**：对齐 metrolist——drain 不预先 `takeIfSame`、改 `allowPending=true`、artwork-ready 判定用 live `controller.metadata`。

### P1-8. Metrolist 取词竞态：`fetchGeneration/fetchJob` 为 volatile check-then-act，可同代双发三源请求

- **位置**：`MetrolistPlayerHooker.kt:48-52,196-245`。
- **理由**：`startLyricsFetchIfNeeded` 可由 onEvents（宿主 player 线程）与 setMetadata before-hook（binder 线程）并发进入，两线程都读到 `fetchGeneration==null` 双双过闸 → 两个 Job 并发打 BetterLyrics/LrcLib/KuGou，且后写者覆盖 `fetchJob`，切歌只能取消最后一个。结果层有 `isGenerationValid` 二次兜底不致错乱，但违反 once-per-generation、浪费网络/电量。
- **修改方向**：把"gate 检查 + cancel + 记账 + launch"合进一把锁，或 `AtomicReference<Pair<Long,Job>>` + CAS。

### P1-9. Parser（LRC/KRC）三处实测正确性缺陷（均来自对编译产物的探针实测）

- **9a `EnhanceLrcParser` 重复时间标签 + 逐字戳 → 整行丢失且自身文本灌入翻译通道**：`parser-lrc/.../EnhanceLrcParser.kt:100-108`（`begin = words.firstOrNull()?.begin ?: ms`），每个重复标签都取第一个词的 begin，随后 `mergeLines` 因 begin 相同把第二行并成第一行的 secondary。实测 `[00:10][00:20]<00:10>Hi<00:10.5>there` → 只剩 1 行、`secondary="Hi there"`。`share/lrckit` 版同病且无 `isUsableTranslation` 兜底。修：begin/end 以各标签自身 ms 为准，多标签时对每个标签平移复制 words。
- **9b `EnhanceLrcDocument.applyOffset` 不平移 `secondaryWords`**：`parser-lrc/.../model/EnhanceLrcDocument.kt:16-29`。含 `[offset:]` 的背景音/副轨逐字，主轨平移而副轨逐字保持旧时间，卡拉 OK 高亮错位正好等于 offset。`share/lrckit` 版更差：不 `coerceAtLeast(0)`，负 offset 产生负时间戳。修：对 `secondaryWords` 做同样平移。
- **9c KRC 逐字数据被丢弃，消费方按索引重扫描对齐**：`parser-krc/.../KrcParser.kt:81-138` 构建了词却只返回行级 `LyricLine`（`richLyricLines` 恒 `words=null`），迫使 kugou（`KuGouKrcFileDecoder.kt:46-48`）、metrolist（`MetrolistLyricDecoder.kt:100-113`）用**不同谓词**过滤后按 index 重新配对——任何"有时间无 `<>` 标签行""去标签后空文本行""乱序行"都会让其后所有行逐字时间错位到相邻行。修：让 `KrcParser` 直接输出带 words 的 `RichLyricLine`，删掉消费方二次扫描。

---

## P2 — 卫生、重复、死代码、测试缺口、契约边缘

### 反射与核心设施复用（契约 §4）
- **未复用 `reflection-core`**：netease/qq/kugou/kuwo/metrolist 用 `singleOrNull`（满足"不盲取第一"，但把 0 候选与多候选塌成同一泛化错误，丢失版本升级歧义诊断）；**apple `firstOrNull` 多候选静默取第一个**（`ApplePlayerHooker.kt:728-757`，直接违反"禁止无约束取首"）。热路径反射（`NeteaseLyricInfoReader`/`QqLyricModelDecoder`/`MetrolistHostMetadata`）每次全量扫方法/沿父类链取字段，`ReflectionCache` 现成却未用。修：结构解析走 `CandidateResolver`，对象读取引入 `ReflectionCache`；kugou/kuwo 补 `:reflection-core` 依赖。

### 跨模块复制粘贴（已致 P1 漂移）
- salt/cone/lx 与 metrolist/poweramp 的 `MediaSessionRegistry`/`MetadataArtwork`/`NativePublisher`/`PendingPolicy`/`ReplayPolicy`/`GenerationController` 六件套近乎逐字节重复。已实证代价：P1-7 poweramp 丢词、下面 ensureBinderSafe 跨 key 混写各存两份、cone/salt 的 `PendingPublicationPolicy` 空判顺序漂移。修：下沉 provider-core 为泛型/参数化组件（cast 过滤、artwork key 集作策略注入），各模块留薄适配层（Debug Settings Activity 已成功下沉，可作先例）。

### metadata 保留边缘（契约 §3.1.2）
- 全部 `newPreservingBuilder`（apple/spotify/kugou/qishui/netease/qq/metrolist/poweramp）用类型白名单，非白名单的自定义 Long/Rating/Bitmap 键经 `getText` 返回 null 被**静默丢弃**且 `runCatching` 吞掉无日志。修：else 分支补 `getLong`/`getRating` 探测，或至少 logDebug 被跳过的 key 名。
- metrolist/poweramp `ensureBinderSafe` 用单一 converted 位图回写全部三个 artwork key（`MetrolistMetadataArtwork.kt:90-125`），循环末尾需转换的 key 赢者通吃、跨 key 串图、给原本没有的 key 凭空加值。修：逐 key 独立转换，不新增 key。

### 时间格式化 Locale（跨 6 处）
- `provider-core/.../ColorOSLyricJsonEncoder.kt:151` 及 kugou/netease/qq/metrolist/ttml 的 `formatLrcTime` 用 `"%02d:%02d.%03d".format(...)` **未指定 `Locale.ROOT`**。在阿拉伯/波斯语等区域 `%d` 产出非 ASCII 数字 → LRC 时间戳损坏、整轨失效。反差：`share/extensions-kt/.../LrcTimeFormatter.kt` 正确用了 `Locale.ROOT`。修：全部改 `String.format(Locale.ROOT, …)`（或复用 `LrcTimeFormatter`）。

### 诊断节流器无界增长
- `StructuredDiagnostics` 的 throttleKey 含 `session`，而多模块把 `session` 设为 `track.id`（netease/qq 等）；`DiagnosticThrottler` 的 `ConcurrentHashMap` 无淘汰、只在 `configure()` 清空 → 长时间播放每首歌留一条 key，缓慢无界泄漏。修：给 throttler 加 LRU/TTL 上限，或节流键用 trackHash 而非原始 id。

### 测试基础设施与覆盖
- **parser 测试自 JUnit 6 起从未运行（已确认）**：`parser-qrc/yrc/krc/ttml` 与 `share/lrckit` 都 `useJUnitPlatform()` + `junit-jupiter 6.0.3` 但**缺 `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`**（只有 parser-lrc 有）。Gradle 9.x 下无 launcher 无法执行，本机 up-to-date 缓存还会伪装成通过——QRC 3DES 解密当前实际零回归保护。修：补 launcher（或抽 convention plugin），CI 强制 `--rerun-tasks`。
- qishui 单测明显不足（5 文件/182 行 vs ~1450 行主码），最高风险的 `QishuiPlayerHooker` 状态机（generation/token 防串曲、STALE 丢弃、负缓存 TTL、700ms 门控）无测试；poweramp `PowerampLyricLoader`（专门留了注入点）与 `PowerampSafUriResolver` 零测试；多处"无断言/直连真实网络"的假测试（`QrcDecrypterTest` println、`YrcDownloaderTest` 打 163 服务器、lrckit `testApplyOffset` 名实不符）。

### 死代码 / 无消费者
- **parser-qrc 整个模块（含 3DES、DP 翻译对齐 900+ 行）无任何生产调用方**（player-qq 自带 `QqLyricModelDecoder`）；provider-core 声明的 `parser-qrc/yrc/krc/ttml` 四条依赖全部 0 import。
- `parser-yrc/.../download/NetEaseCrypto.kt` 整文件死代码（yrckit 有活的同款）；`share/lrckit` 的 `EnhanceLrcParser/EnhanceLrcDocument`、`share/yrckit` 的 `YrcParser`、`extensions-kt` 的 `findClosest<T>` 均无生产消费者。
- 零散死代码：qq/kugou `pendingHostWrite` ThreadLocal（只写不读）、kugou `TRANSLIT_METHODS/FIELDS`、apple/spotify/metrolist/poweramp 的 `rawLyric/translationLyric` 死字段、`PowerampPendingPublicationStore.take()`、cone `ConeLyricSource.LYRICON/PARSER` 死枚举、kuwo `KuWo(tag)`。

### lrckit/yrckit ↔ parser-* 重复且已分叉
- `LrcParser` 两份 100% 相同、`YrcParser` 两份仅返回类型不同、`EnhanceLrcParser` ~85% 重叠但已产生三处行为分叉（lrckit 词间强插空格、无 bracket-inline 支持、applyOffset 语义不同）。同一 bug 要修两遍（P1-9a 即现成例子）。修：让 kit 薄化为对 parser-* 的适配层，kuwo 直接改用 parser-lrc 后删 lrckit 解析逻辑。

### TTML 专项（`parser-ttml/.../TtmlParser.kt`）
- 忽略 `<p>` 的 end 属性，无逐字行的行长一律伪造为 `begin+5000`（:152），破坏 `RichLyricLine.end` 契约；pretty-printed TTML（span 独占一行）因 `:73-76` 把换行判为"无分隔"→ `LatinSyllableSpanMerger` 把相邻拉丁词粘成 `HelloWorld`；`ttm:role` 按字面前缀匹配（非命名空间），非 `ttm` 前缀绑定时 bg/翻译 span 会混入正文；未 `disallow-doctype-decl`（内部实体膨胀仍可构造）。

### 构建卫生
- serialization 编译器插件 **`2.1.21` 硬编码于 22 个 build 文件**，而 catalog 的 Kotlin 是 `2.3.20`（`libs.versions.toml:18`）——版本错配 + 绕过 catalog；`ksp=2.3.6` 取值也待核。
- `provider-core`/`reflection-core` 的 `proguardFiles(..., "proguard-rules.pro")` 指向**不存在的文件**（目录仅有 `consumer-rules.pro`），部分 AGP 会报错；两者是全体 app 依赖。
- proguard 三套模板漂移，**`kuwo`/`salt` 在 `isMinifyEnabled=true` 下却缺 `-keep <player>.**` 与 `-keep HookEntry`**，且带 `-repackageclasses ''`——Xposed 入口是否被 Yuki consumer 规则保住需复核，否则 release 混淆可能重命名入口导致模块加载失败。
- 12 个 app 模块 build 文件 40+ 行逐字重复（签名/buildTypes/compileOptions/SDK），应抽 convention plugin；已现漂移（salt `versionCode=5` 其余 =1、`testOptions` 部分缺失）。
- `.gitignore` 未覆盖 `/.serena/`、`.kotlin/` 仅部分忽略；`.idea/` 20 个文件已入库；kuwo 声明未使用的 serialization 依赖。

### kuwo 遗留债务（todo 已知）
- 未迁移到 `NativeLyricInfoPublisher`/`ColorOSLyricJsonEncoder`（自造 encoder 无 payload 上限）、模块名仍 `:kuwo-music`、未依赖 `reflection-core`（`KuWoDexKitResolver` 多候选静默降级 + 裸 `System.loadLibrary`）、debug 关闭时 `logLyricTimingSample` 仍无条件拼接逐字串（`KuWo.kt:460,506-521`，违反"关闭时近零开销"）、原始 `XposedBridge.hookMethod` 回调（`KuWo.kt:261-285`）缺顶层 `runCatching` 兜底。

### 隐私（debug 级，仍属契约偏差）
- lx `LxArtworkDiagnostics.kt:52-57`、poweramp `PowerampArtworkDiagnostics.kt:53-55`、kugou `KuGouPlayerHooker.kt` 多处 debug 日志输出封面 URI 原文/本地路径尾 96 字符/明文 title；`SensitiveFieldRedactor` 只遮 `/data/user/` 不遮 `/storage/emulated/`、`file://`。契约未给 debug 例外。修：URI 只记 scheme+host+hash，Redactor 增补 `/storage/`、`file://` 规则。

### 并发（多为可见性/重复工作，非状态损坏）
- apple/spotify/metrolist/qishui/kugou 的 hooker 级计数器与 fetch 启停序列存在 `@Volatile` check-then-act（`requestAttempts++`、fetch cancel+assign+launch、`scheduledPlaybackRefreshGeneration`、`lastLyricReadyGeneration` 锁外读）。修：纳入同一把锁或原子 CAS。

### 超出"只覆写 lyricInfo"范畴的宿主改写（需与契约方确认措辞）
- salt `SaltPlayerHooker.kt:87-115` 对媒体键 `onReceive` `resultNull()` 吞掉宿主处理、改由模块 `startForegroundService`+600ms 补发 play——改写了宿主播放控制流且未文档化，失败时静默丢键。
- lx `LxMetadataArtwork.kt` 改写 TITLE/ARTIST/ALBUM 与封面位图——据 `PHASE-4-LX-MIGRATION-REPORT` 是有意的"蓝牙身份还原 + binder-safe 封面"，功能属还原而非破坏，但与契约字面"其他 metadata 原样保留"冲突，建议修订契约措辞明确其含义。

---

## P3 — 风格与细节（择要，另有约十余处同类）

- **QQ 魔改 DES S 盒无注释**（`parser-qrc/.../DESHelper.kt:22,38` 故意偏离标准 DES），静态分析或后人"纠错"即静默破坏全部 QRC 解密——务必加 `// 故意偏离标准DES，勿改`。
- spotify `SpotifyAdvertisementPolicy.kt:22` 的 `"骞垮憡"` 是"广告"UTF-8 被错误码页重解码的乱码死分支；netease `findMusicInfo` 多候选无匹配时 `first()` 兜底可能取上一首；qq `QqSongInfoReader.kt:58` 用魔法值 `"0"/"1"` 过滤会误伤合法曲名。
- apple `normalizeDuration` 魔数 86400 对已是毫秒的短曲（<86.4s）误 ×1000（`AppleTrackIdentity.kt:52-55`，单测未覆盖该分支）。
- 模型全 `var` + `duration` 冗余存储（应改 `val get()=(end-begin)`）、逐字词跨行别名共享可变实例；`printStackTrace`、恒真判断、失效 `@Suppress`。
- INTERNET 权限声明不一致（metrolist/spotify 声明但对 hook 路径无效，联网的 netease 反而没声明）；`gradle.properties:6` 的 `-XX:+CMSClassUnloadingEnabled` 在 JDK21 已移除；kuwo 4 个文件 + qishui `arrays.xml` 缺 Apache-2.0 版权头；平台级 MediaSession hook 对宿主全部 session 无差别注入翻译按钮。

---

## 已核对无问题（抽样，非全部）

不改写 `setPlaybackState` 的状态语义（全仓 grep 确认，仅受控 CustomAction 增删与 poweramp/qishui 文档化特例）；无 NPatch 运行时残留（`NPATCH_EMBEDDED`/`npatch_marker`/`notifyNpatchEmbeddedActive` 源码 0 命中）；无 Bridge/v4 广播/词幕运行时挂载；`RuntimeModeResolver` 非 Xposed 即 fail-closed；**Spotify 认证 token 值绝不落日志**（只记 key 名）；网易 eAPI 无 CookieJar/鉴权头、该层无日志；罗马音/音译在全部模块都不进翻译通道（多处单测）；`NativeLyricInfoPublisher` 的 Parcel fail-open、空 Builder 拷贝、封面 binder-safe 降采样正确；`DexKitBridge` 在除 apple 外均 `.use{}` 释放；LICENSE/NOTICE 完整并保留 Proify/Tomakino 署名；签名全走 `System.getenv`、无硬编码密钥、CI secrets 不回显；xposed manifest 元数据齐全、scope 精确到目标包。

---

## 建议修复顺序

1. **P1-1 先提交仓库**（其余修复都依赖一个可信基线）。
2. **P0-1 日志改 hash**（一次性跨模块，改调用点，加架构测试锁死）。
3. **P1-2 补 Parcel 闸门** + **P1-3 关 Apple DexKit** + **P1-4 Poweramp 接收器改 NOT_EXPORTED**（低成本、消真实风险）。
4. **P1-5/6/7 Poweramp 三项** 与 **P1-8 Metrolist 竞态**、**P1-9 parser 三处**（配合下面第 5 步补测）。
5. **P2 补 parser 测试 launcher**（否则改 parser 无回归保护）→ 再下沉六件套与保留式拷贝到 provider-core，从根上消除复制漂移。
6. 其余 P2/P3 随重构批次清理；serialization 插件版本与 proguard 模板作为构建收口一并处理。

---

## 整改回填（2026-08-28）

本报告之后已完成一轮源码整改，并先将 4.0 迁移基线提交为 `41505eb`，配置远端
`https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Providers.git`。本节只描述本轮实际落地内容；
原始审查结论保留作为问题证据。

- **P0 已关闭**：spotify/apple/netease/qq/kugou/qishui/metrolist 的常开诊断不再写入
  title/artist/mediaId 原文，统一使用 `DiagnosticHasher` 的 `trackHash`；封面 URI 与本地路径也改为
  scheme/host/value hash，并补 `/storage`、`file://` 脱敏。
- **P1 已关闭**：官方追加路径共享 `MetadataParcelGuard`；Apple DexKit 改为 `withDexKit` 自动释放；
  Poweramp 接收器改为 `RECEIVER_NOT_EXPORTED`，本地歌词增加有界 BOM/UTF-8/UTF-16/GB18030 解码，
  translation poke 改为原子单代记账并要求已有 `lyricInfo`，pending drain 对齐 live metadata；
  Metrolist fetch 门禁原子化；LRC 重复标签/副轨 offset 与 KRC 逐字归属均已修复并补回归测试。
- **P2/P3 本轮已收口的明确项**：metadata 自定义 typed key 保留、逐 artwork key binder-safe 转换、
  `Locale.ROOT` 时间格式、诊断节流器有界化、六个 JVM parser/kit 的 launcher、`testV5Matrix` CI 门禁、
  serialization 插件与 Kotlin 版本对齐、缺失/漂移 ProGuard、`.idea/.serena/.kotlin` 卫生、TTML end/
  namespace/DOCTYPE/pretty-print、Apple 短曲时长、QQ 数字曲名、NetEase 多候选 fail-closed、QRC S 盒注释。
- **保留为后续架构批次**：六件套全面下沉、所有热路径反射统一迁入 `ReflectionCache`、lrckit/yrckit
  薄适配化、app convention plugin、删除目前仍作为独立库保留的 parser-qrc，以及 qishui/Poweramp SAF
  的进一步测试扩充。这些不阻塞本轮 P0/P1 关闭，但仍属于报告中的长期重复/覆盖债务。

本地验证：`testV5Matrix` 汇总 458 个测试，0 failure/0 error；`assembleV5MatrixDebug` 生成 12 个 APK。

### Poweramp P1-4 真机回归补充（2026-08-29）

`lyrics-log-20260829-014011.txt` 证明，目标机上 `RECEIVER_NOT_EXPORTED` 只能在注册时取得系统保存的
sticky `TRACK_CHANGED`；后续两次切歌仅由 MediaSession metadata 推进 generation，没有新的
`TRACK_BOUND` 或本地歌词加载，旧 pending 因曲目变化被丢弃。该行为推翻了 P1-4 中“同 UID 广播
必然覆盖后续切歌”的未验证假设。

修复保留 `RECEIVER_NOT_EXPORTED`，不重新开放可伪造广播；改为在 Poweramp 宿主进程内只读截获
`ContextWrapper.sendStickyBroadcast(Intent)`，仅处理 action 精确等于
`com.maxmpz.audioplayer.TRACK_CHANGED` 的宿主发送，并以广播 `ts` + track key 去重 hook/receiver
双通道。`player-poweramp:testDebugUnitTest` 共 29 个测试通过，Poweramp Debug APK 构建及 v2 签名
验证通过；切歌取词链仍需用户真机复核。

Windows 中文工作区下 Gradle 9.3.1 的纯 JVM test worker 会错误地 `ClassNotFoundException`，本地验证通过
临时 ASCII 盘符映射执行；GitHub Actions 工作区不含该路径条件，并已强制 `--rerun-tasks`。以上均为
本地构建/静态验证，未替代既有真机结论，也未新增真机验证。
