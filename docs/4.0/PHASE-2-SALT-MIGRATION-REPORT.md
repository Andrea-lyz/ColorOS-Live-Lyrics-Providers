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

## Salt 取证 fixture

- 保留 `androidx.obf` 与 `androidx.media3` 双包根。
- 保留字面 `invokeSuspend` 优先、唯一单 `Object` fallback、零/多候选分类报错。
- 12.2.1 fixture：publisher `androidx.media3.ju1#庄`。
- 12.3.0 fixture：source `androidx.media3.ac1`、scroll `androidx.media3.bc1`、result
  `androidx.media3.zb1`、publisher `androidx.media3.tv1#迉(Object)`。
- 12.3.0 APK SHA-256：
  `00F8731228DAD117E3416E0BFC7EC201516488A6389217F92B9401BCEB74CCAF`。

## 自动验证

执行：

```text
scripts\gradle-ascii.cmd :reflection-core:testDebugUnitTest :player-salt:testDebugUnitTest :player-salt:assembleDebug :player-salt:assembleNpatch :player-salt:assembleNpatchDebug --rerun-tasks --no-configuration-cache
```

结果：`BUILD SUCCESSFUL in 1m 51s`，267 个 task 执行。JUnit XML 共 12 suites、
41 tests，0 failures、0 errors、0 skipped。

最终 APK：

| Variant | 绝对路径 | SHA-256 | NPATCH | Debug marker |
|---|---|---|---|---|
| debug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\debug\player-salt-1.0.4-debug.apk` | `7F635656D0E4D5E119951254339DC5E1D5F56115965817D06624A43609469C2A` | false | false |
| npatch | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatch\player-salt-1.0.4-npatch.apk` | `2795A1EAE84779809DB026917B3A7F02A1D87786FA8B85AF0A461D431DB3313B` | true | false |
| npatchDebug | `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\ColorOS-Live-Lyrics-Providers\build\all-apks\npatchDebug\player-salt-1.0.4-npatchDebug.apk` | `97D8BE9361364E93FD5DAD45EB07B13006B9CC069CF4C458E8C3C4894E240F19` | true | true |

三种 APK 的 package 均为 `io.github.andrealtb.coloroslyrics.provider.salt`，versionCode=5、
versionName=1.0.4。解包扫描均未发现 `LyriconFactory`、`LyriconProvider`、
`SystemUiBroadcastSender`、`EXTERNAL_LYRIC_DIRECT_V4` 或 `player.setSong`。

## 未验证门禁

以上为源码、单元测试、Gradle 构建和 APK 静态验证。Root 与 NPatch patched-host 的真机
安装、DexKit 发现、主/辅助 session 运行时选择、pending drain、metadata replay、媒体按键
及锁屏显示效果均未在设备上验证，不宣称真机通过。
