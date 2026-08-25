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

## 车载/蓝牙歌词 metadata 源码边界

- 取证日志：`logs/salt-phase2-root-01.txt`、`salt-phase2-root-02.txt`、`salt-phase2-root-03.txt`。
- Salt 源码 `androidx.media3.RunnableC0300` 的 metadata 构建链确认：车载/蓝牙歌词与普通播放共用同一个主
  `MediaSession`。开启车载歌词时，Salt 在每一行更新中直接设置：

  ```text
  TITLE  = 当前动态歌词行
  ARTIST = 原歌手 + 分隔符 + 原曲名
  ```

  部分版本同时通过 `DISPLAY_TITLE` / `DISPLAY_SUBTITLE` 保留原曲名和歌手，随后调用同一个
  `MediaSession#setMetadata`。因此不存在可通过 session 分流解决的独立“车载 Session”。
- `salt-phase2-root-03.txt` 证明基于上一份 stable metadata 的 relay 状态机仍会遇到交替
  `SALT_RELAY_METADATA_NORMALIZED` / `TRACK_MISMATCH`，不能作为稳定数据边界。
- 最终实现改为无状态 identity resolver：
  1. 优先从 `DISPLAY_TITLE` / `DISPLAY_SUBTITLE` 获取稳定曲目身份；
  2. display 字段不可用时，从 `<Artist> -|–|— <Title>` 复合 ARTIST 恢复身份；
  3. 普通 metadata 才直接使用标准 TITLE / ARTIST；
  4. 动态歌词 TITLE 永不进入 `TrackGenerationPolicy`，同曲逐行更新只刷新当前 host metadata；
  5. 真实 ID、原曲名或歌手变化仍推进 generation；
  6. 首次发布、pending drain 和 replay 始终复制 Salt 当前 metadata，仅追加 `lyricInfo`，不改写车载动态字段。
- 该边界不再缓存或合成另一份 stable metadata，也不依赖前一包 relay 是否被接受，从源头隔离车载歌词展示字段与
  锁屏歌词曲目身份。

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
scripts\gradle-ascii.cmd :player-salt:testDebugUnitTest :player-salt:assembleDebug --rerun-tasks --no-configuration-cache
scripts\gradle-ascii.cmd :player-salt:assembleNpatch :player-salt:assembleNpatchDebug --no-configuration-cache
```

结果：两次命令均 `BUILD SUCCESSFUL`。JUnit XML 共 11 suites、43 tests，0 failures、0 errors、0 skipped。

最终 APK：

| Variant | 绝对路径 | SHA-256 | NPATCH | Debug marker |
|---|---|---|---|---|
| debug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\debug\player-salt-1.0.4-debug.apk` | `F11147A18D687674472E98454E1548AC9A3C59511D32E2D02E1E99595312098E` | false | false |
| npatch | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatch\player-salt-1.0.4-npatch.apk` | `744F038F3EDDA8FB64A454634BF92B1D91D1FD527CFB4F37B3EB1D26B053C318` | true | false |
| npatchDebug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatchDebug\player-salt-1.0.4-npatchDebug.apk` | `2909E47308140F6426E4C2D779FC1712025FCED624DD8A9EB0603D8442BEC9D0` | true | true |

三种 APK 的 package 均为 `io.github.andrealtb.coloroslyrics.provider.salt`，versionCode=5、
versionName=1.0.4。解包扫描均未发现 `LyriconFactory`、`LyriconProvider`、
`SystemUiBroadcastSender`、`EXTERNAL_LYRIC_DIRECT_V4` 或 `player.setSong`。

## 未验证门禁

以上为源码、单元测试、Gradle 构建和 APK 静态验证。`logs/salt-phase2-root-01.txt`、
`logs/salt-phase2-root-02.txt` 与 `logs/salt-phase2-root-03.txt` 对应的 Root 与 NPatch
真机运行、蓝牙歌词中继下的锁屏与 AOD 显示、DexKit 发现及 metadata replay 待真机复测，不宣称真机通过。
