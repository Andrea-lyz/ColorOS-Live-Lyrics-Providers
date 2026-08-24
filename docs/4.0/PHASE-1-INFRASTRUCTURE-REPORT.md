# 4.0 Phase 1 基础设施实施报告

记录日期：2026-08-24  
仓库：`ColorOS-Live-Lyrics-Providers`  
分支：`4.0`  

## 1. 交付成果概览

根据 `todo.md` 4.0 规划，已在独立 Provider 仓库中完成 Phase 1 基础设施搭建及纯词幕交付物剔除。旧播放器 module 继续作为 Phase 2–4 的只读迁移源保留；其 Lyricon 注册随对应新 player module 验收后删除，不再被误记为 Phase 1 已迁移源码。

### 1.1 核心与反射模块
- **`provider-core`** (`io.github.andrealtb.coloroslyrics.provider.core`)：
  - `TrackIdentityPolicy`：歌曲身份标准化与比对（支持 ID、标题、歌手、时长容差匹配）。
  - `TrackGenerationPolicy`：单向递增 generation 计数器，严格防止旧会话异步回调污染。
  - `LyricTimingClassifier`：准确分类歌词时间轴格式（`WORD` / `LINE` / `UNTIMED_TEXT` / `INVALID`）。
  - `LyricLaneAlignmentPolicy`：逐行单调递增时序对齐、一对一非重复翻译消费、`//` 空翻译过滤、推广行过滤防脱节。
  - `RuntimeModeResolver`：进程内单次评估 `ROOT_MODULE` / `NPATCH_EMBEDDED` / `UNKNOWN`；Manifest/resource marker 均有测试，root 与 NPatch 信号同时出现时按冲突 fail-closed。
  - `NativeLyricInfoPublisher`：先在原 metadata 副本上构造候选并完成宿主包、当前 track generation、字段长度和完整 Parcel 检查，全部通过后才修改调用方 Builder；任何拒绝路径均不污染原 metadata。
  - `StructuredDiagnostics`：统一 `[CLL] level/component/area/event` 字段格式，支持 Logcat / Xposed sinks、按 component/area/event/session 节流、敏感字段脱敏和 track identity SHA-256 摘要。
  - `ProviderDebugConfig`：覆盖 15 个旧迁移目标及新增 Cone 的独立 `ProviderId`；ROOT 配置源、NPatch 打包 marker 配置源和 `UNKNOWN` 默认关闭行为彼此隔离。

- **`reflection-core`** (`io.github.andrealtb.coloroslyrics.provider.reflection`)：
  - `ReflectionCache`：按 ClassLoader 及宿主版本隔离的反射缓存，任一变化时自动清空。
  - `CandidateResolver`：严格候选解析器，禁止盲目 `firstMethod`；0 候选报 `ReflectionNotFoundException`，多候选报 `ReflectionAmbiguityException` 并输出完整候选签名。
  - `DexKitBridge`：DexKit 2.2.0 会话生命周期安全包装与反射交接。

### 1.2 歌词解析模块
- **`parser-lrc`** (`io.github.andrealtb.coloroslyrics.provider.parser.lrc`)：标准与增强 LRC 解析器，支持毫秒容差与方括号保护。
- **`parser-qrc`** (`io.github.andrealtb.coloroslyrics.provider.parser.qrc`)：QRC XML / 纯文本解析、Triple-DES 解密与翻译动态规划对齐。
- **`parser-yrc`** (`io.github.andrealtb.coloroslyrics.provider.parser.yrc`)：YRC 逐字解析与时间轴前摇保护。
- **`parser-krc`** (`io.github.andrealtb.coloroslyrics.provider.parser.krc`)：KRC XOR 解密、Zlib 解压与 Base64 语言元数据解析。
- **`parser-ttml`** (`io.github.andrealtb.coloroslyrics.provider.parser.ttml`)：W3C / BetterLyrics / Apple TTML 解析、Latin 音节合并与 CJK 逐字时序提取。

### 1.3 纯词幕模块剔除
已从仓库完全移除以下无 v5 目标的纯词幕模块，并在 `settings.gradle.kts` 中注销：
- `cloud-provider`
- `meizu-provider`
- `car-provider`
- `share:meizu-provider`
- `share:car-provider`

旧 `apple-music`、`163-music`、`salt-player-music` 等 application module 仍保留旧包名与 Lyricon 职责，仅用于后续逐播放器迁移和 A/B 基线。它们不是 4.0 新 namespace 已完成的 player 交付物。

## 2. NPatch 与版权交付物

- `provider-core` 提供默认关闭的 Manifest placeholders、`npatch_marker` 资源及 NPatch debug marker。
- marker 配置与初始化顺序记录于 `docs/4.0/NPATCH-RUNTIME-MARKERS.md`。
- 根目录 `NOTICE` 记录 LyricProvider 来源、基线 commit 和 Apache-2.0 署名。

## 3. 单元测试验证状态

运行指令：
```powershell
scripts\gradle-ascii.cmd :parser-lrc:test :parser-qrc:test :parser-yrc:test :parser-krc:test :parser-ttml:test :reflection-core:testDebugUnitTest :provider-core:testDebugUnitTest --no-configuration-cache
```

结果以 Gradle JUnit XML 汇总为准：
- `parser-lrc`：15 项测试全部通过。
- `parser-qrc`：7 项测试全部通过。
- `parser-yrc`：2 项测试全部通过。
- `parser-krc`：1 项测试全部通过。
- `parser-ttml`：3 项测试全部通过。
- `reflection-core`：6 项测试全部通过。
- `provider-core`：33 项测试全部通过。
- 合计 67 项测试，0 failure、0 error、0 skipped。
- 新仓库全量 `assembleDebug` 成功：688 个 actionable tasks，其中 316 executed、243 from cache、129 up-to-date。

## 4. Phase 1 验收边界

- 基础设施与 parser module：静态实现及 JVM 测试完成。
- 旧播放器 application module：仅保留为迁移源，未宣称完成新 namespace、v5 或 NPatch。
- NPatch：marker/config 契约和测试完成，播放器真机注入验证从 Phase 2 开始。
- 构建成功不等于真机验证；Phase 1 不包含播放器 runtime 验收。

## 5. 下一步规划（Phase 2）
进入 Phase 2：Salt Player 与 ConePlayer 适配迁移（新建 `player-salt`、`player-cone`，迁移 DexKit 发现逻辑与 v5 原生发布，验证 root/NPatch 二合一）。
