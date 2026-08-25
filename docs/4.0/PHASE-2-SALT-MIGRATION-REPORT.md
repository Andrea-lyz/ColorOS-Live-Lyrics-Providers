# 4.0 Phase 2 Salt Player 迁移报告

记录日期：2026-08-25
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）
模块：`player-salt`

## 交付边界

- 新建 `player-salt`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.salt`，并加入 `settings.gradle.kts`。
- 未修改或删除 Bridge 的 Salt scope、`SaltPlayerAdapter`、翻译按钮实现，未修改
  `todo.md` 或 `PlayerSource`。
- `desktop_lyrics` 仍由 Bridge/SystemUI 的 `TranslationToggleMediaActionBinder` 识别并
  绑定；Provider 仅保留动作常量，不宣称迁移该持久职责。
- 当前解析器未解析 Salt `va1` 逐字模型；当前能力为 **LINE-only**，不能标记为 WORD。

## 运行时与发布路径

- Root 构建的 `BuildConfig.NPATCH_EMBEDDED=false`，入口调用
  `notifyXposedHookActive()`；NPatch 两个构建为 `true`，不发送 root 信号。
  root+npatch 同时出现仍由 Phase 1 `RuntimeModeResolver` fail-closed 为 `UNKNOWN`。
- Root debug 通过 module-owned `XSharedPreferences` 读取 Salt 独立 debug 配置，读取失败
  默认 false；NPatch 只读取宿主打包 marker。
- MediaSession 使用弱引用 registry，跟踪 constructor tag、host metadata、播放状态、
  active/release；只有与 publication 稳定身份匹配且唯一、有效的 session 才发布。
- generation 只由 host 主 session metadata 驱动。旧回调不会把自身曲目设置成当前曲目。
- 最多缓存一条不含 metadata 的 pending publication。host track 尚未建立，或同曲但
  session/metadata 尚未就绪时暂存；后续 metadata/playback/active 事件满足唯一 session、
  同曲和有效 generation 后立即 drain。不同曲、换曲和替换均清理并记录结构化日志。
- 模块仅缓存当前 generation 的 publication/lines。宿主同曲 metadata 刷新缺失本模块
  `lyricInfo` 时，可在同一已绑定 session 上有界 replay；不跨 session、不覆盖外部 payload，
  并使用 reentrancy guard。
- `ReflectionCache` 按当前 classLoader 和 hostVersion 缓存 Song getters 与 publisher method；
  classLoader/version 变化时清空。
- `reflection-core.DexKitBridge` 在创建 native bridge 前线程安全、一次性加载 `dexkit`。

## 车载/蓝牙歌词中继（Relay）与 Generation 1→13 根因修复

- 取证日志：`logs/salt-phase2-root-01.txt`。
- 根因分析：
  - Salt 播放器内置车载/蓝牙歌词中继（Relay）功能。开启后，Salt 会周期性调用 `MediaSession#setMetadata`，
    将 `METADATA_KEY_ARTIST` 改写为 `<Artist> -|–|— <Title>`（取第一个分隔符），并将 `METADATA_KEY_TITLE`
    改写为当前动态播放的歌词文本行。
  - 在初版 Phase 2 实现中，`SaltPlayerHooker.setMetadata` 的 before hook 未对 relay metadata 进行隔离，
    每次传入均由 `trackFrom(incoming)` 提取出包含动态歌词标题的虚假新 TrackIdentity，并触发
    `observeUniqueHostMainTrack() -> policy.onTrackObserved(track)`。
  - 这导致每更新一行歌词，track generation 便递增一次，在日志中出现从 generation 1 持续递增至 generation 13
    （`SALT_FINAL_STALE generation=13`）。随之产生的后果是：未就绪的 pending 歌词被判定为换曲而丢弃
    （`PENDING_DROPPED_TRACK_CHANGE`），后续真实歌词发布也因 generation 过期被丢弃（`STALE`）。
- 修复策略与隔离边界：
  1. `SaltBluetoothLyricRelayPolicy.parseRelayIdentity` 基于 Bridge v4 真实格式规则，检测 `<Artist> -|–|— <Title>`
     复合艺术家字符串并提取原始曲目身份。
  2. `SaltPlayerHooker.setMetadata` before hook 拦截 relay metadata：
     - 若 registry 中存在该 session 的 `stableMetadata`，且解析出的 relay 身份（曲名、歌手、时长）与 stable 匹配，
       则接受为 relay，调用 `sessions.onRelayMetadata(session, incoming)`。
     - 禁止调用 `onHostMetadata`，禁止调用 `observeGenerationFromHostMainSession()` 推进 generation。
     - relay 更新不得覆盖 `stableMetadata`，保持 generation 与 stable base metadata 稳定。
     - 有界 replay 仍会将模块生成的 `lyricInfo` 附着在 incoming metadata 上，使 ColorOS SystemUI 能正常显示歌词，
       同时不修改 incoming 的动态标题/复合艺术家，确保车载蓝牙设备显示正常。
     - 若无 stable metadata（如冷启动首包即为 relay）或身份不匹配，记录 `SALT_RELAY_METADATA_REJECTED` 并保持 fail-open，
       不写入 stable metadata 且不推进 generation。
  3. 真实换曲时（非 relay 正常 metadata），更新 `stableMetadata`、推进 generation、重置 replay 快照并在 stable 建立后
     drain pending publication。

### 冷启动首包 Relay 补充修复

- `logs/salt-phase2-root-02.txt` 进一步确认：Salt 冷启动后第一份 metadata 就可能是 relay，日志持续出现
  `SALT_RELAY_METADATA_REJECTED reason=NO_STABLE_TRACK`，同时歌词停留在
  `SALT_FINAL_PENDING_STORED/PENDING_REPLACED generation=0`。因此仅等待普通 stable metadata 会永久阻塞发布。
- 当首包 relay 的 `<Artist> - <Title>` 与已经捕获的 pending Salt publication 身份、时长一致时，Provider 现在：
  1. 从 pending publication 恢复 stable track identity；
  2. 只在 registry 中保存由 relay metadata 还原出的 stable base，不覆盖 Salt 正在提交的动态 relay metadata；
  3. 建立 host-driven generation；
  4. 将 pending 歌词直接附着到当前 relay metadata 后交给原始 `setMetadata` 调用，保留车载/蓝牙动态标题。
- 无 pending publication、身份不一致或时长不兼容时继续 fail-open，不借用旧曲身份。

## Salt 取证 fixture

- 保留 `androidx.obf` 与 `androidx.media3` 双包根。
- 保留字面 `invokeSuspend` 优先、唯一单 `Object` fallback、零/多候选分类报错。
- 12.2.1 fixture：publisher `androidx.media3.ju1#庄`。
- 12.3.0 fixture：source `androidx.media3.ac1`、scroll `androidx.media3.bc1`、result
  `androidx.media3.zb1`、publisher `androidx.media3.tv1#迉(Object)`。
- 12.3.0 APK SHA-256：
  `00F8731228DAD117E3416E0BFC7EC201516488A6389217F92B9401BCEB74CCAF`。
- Relay 解析 fixture：
  - `William Black/Fairlane - Broken` -> artist="William Black/Fairlane", title="Broken"
  - `Porter Robinson - Kitsune Maison Freestyle - Live` -> artist="Porter Robinson", title="Kitsune Maison Freestyle - Live"
  - `Adele - All I Ask` -> artist="Adele", title="All I Ask"

## 自动验证

执行：

```text
scripts\gradle-ascii.cmd :player-salt:testDebugUnitTest :player-salt:assembleDebug :player-salt:assembleNpatch :player-salt:assembleNpatchDebug --rerun-tasks --no-configuration-cache
```

结果：`BUILD SUCCESSFUL`，262 个 task 执行。JUnit XML 共 11 suites、45 tests，0 failures、0 errors、0 skipped。

最终 APK：

| Variant | 绝对路径 | SHA-256 | NPATCH | Debug marker |
|---|---|---|---|---|
| debug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\debug\player-salt-1.0.4-debug.apk` | `B5E83A88E08053C8F5499020ABD93E9FE35E26B308A2747162F533121E4D69C5` | false | false |
| npatch | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatch\player-salt-1.0.4-npatch.apk` | `97FA4862EA9CB4A580FAD8A754B8244106B252D9FD918B76BE56F3E4CF6E8BAD` | true | false |
| npatchDebug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatchDebug\player-salt-1.0.4-npatchDebug.apk` | `7015E6F848563F597FD67AFADB2CA6068E264A562EE025F2C9C8C36144F7FD14` | true | true |

三种 APK 的 package 均为 `io.github.andrealtb.coloroslyrics.provider.salt`，versionCode=5、
versionName=1.0.4。解包扫描均未发现 `LyriconFactory`、`LyriconProvider`、
`SystemUiBroadcastSender`、`EXTERNAL_LYRIC_DIRECT_V4` 或 `player.setSong`。

## 未验证门禁

以上为源码、单元测试、Gradle 构建和 APK 静态验证。`logs/salt-phase2-root-01.txt` 与
`logs/salt-phase2-root-02.txt` 对应的 Root 与 NPatch
真机运行、蓝牙歌词中继下的锁屏与 AOD 显示、DexKit 发现及 metadata replay 待真机复测，不宣称真机通过。
