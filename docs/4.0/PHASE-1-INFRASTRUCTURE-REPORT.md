# 4.0 Phase 1 基础设施实施报告

记录日期：2026-08-24  
仓库：`ColorOS-Live-Lyrics-Providers`  
分支：`4.0`  

## 1. 交付成果概览

根据 `todo.md` 4.0 规划，已在独立 Provider 仓库中完成 Phase 1 基础设施搭建及词幕模块剔除：

### 1.1 核心与反射模块
- **`provider-core`** (`io.github.andrealtb.coloroslyrics.provider.core`)：
  - `TrackIdentityPolicy`：歌曲身份标准化与比对（支持 ID、标题、歌手、时长容差匹配）。
  - `TrackGenerationPolicy`：单向递增 generation 计数器，严格防止旧会话异步回调污染。
  - `LyricTimingClassifier`：准确分类歌词时间轴格式（`WORD` / `LINE` / `UNTIMED_TEXT` / `INVALID`）。
  - `LyricLaneAlignmentPolicy`：逐行单调递增时序对齐、一对一非重复翻译消费、`//` 空翻译过滤、推广行过滤防脱节。
  - `RuntimeModeResolver`：进程内单次评估 `ROOT_MODULE` / `NPATCH_EMBEDDED` / `UNKNOWN`；`UNKNOWN` 模式严格 fail-closed。
  - `NativeLyricInfoPublisher`：只发布到目标播放器自身的 `MediaMetadata["lyricInfo"]`；保留封面/标题/URI等其他元数据；超过 512 KiB 时 fail-open 拒绝注入歌词并保留原元数据。
  - `StructuredDiagnostics`：统一日志体系（Logcat / Xposed Sinks），支持滑动窗口节流器 `DiagnosticThrottler` 与敏感数据脱敏 `SensitiveFieldRedactor`。
  - `ProviderDebugConfig`：全 Provider 默认关闭（`false`）的调试配置读取器与安全回退逻辑。

- **`reflection-core`** (`io.github.andrealtb.coloroslyrics.provider.reflection`)：
  - `ReflectionCache`：按 ClassLoader 及版本隔离的反射缓存，ClassLoader 变更时自动失效。
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

## 2. 单元测试验证状态

运行指令：
```powershell
scripts\gradle-ascii.cmd :parser-lrc:test :parser-qrc:test :parser-yrc:test :parser-krc:test :parser-ttml:test :reflection-core:testDebugUnitTest :provider-core:testDebugUnitTest --no-configuration-cache
```

结果：
- `parser-lrc`：10 项测试全部通过。
- `parser-qrc`：4 项测试全部通过。
- `parser-yrc`：2 项测试全部通过。
- `parser-krc`：1 项测试全部通过。
- `parser-ttml`：3 项测试全部通过。
- `reflection-core`：4 项测试全部通过。
- `provider-core`：10 项测试全部通过。
- 全量 34 项单元测试 100% 通过。
- 既有播放器模块（`:163-music:assembleDebug`, `:salt-player-music:assembleDebug`）构建正常。

## 3. 下一步规划（Phase 2）
进入 Phase 2：Salt Player 与 ConePlayer 适配迁移（新建 `player-salt`、`player-cone`，迁移 DexKit 发现逻辑与 v5 原生发布，验证 root/NPatch 二合一）。
