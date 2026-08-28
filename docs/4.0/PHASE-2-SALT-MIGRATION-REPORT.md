# 4.0 Phase 2 Salt Player 迁移报告

记录日期：2026-08-25
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）
模块：`player-salt`

## 交付边界

- 新建 `player-salt`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.salt`，并加入 `settings.gradle.kts`。
- Salt 仅以 **Root / LSPosed** 作为 4.0 运行路径。用户确认 Root 等价与冲突评审完成后，
  Phase 2 已删除 Bridge 的 Salt scope、`SaltPlayerAdapter` 与定向测试；按播放器包名保存的
  翻译偏好和 SystemUI 翻译按钮设置继续保留。
- `desktop_lyrics` 仍由 Bridge/SystemUI 的 `TranslationToggleMediaActionBinder` 识别并
  绑定；Provider 仅保留动作常量，不宣称迁移该持久职责。
- 当前 Provider 未直接解析 Salt `va1` 私有对象；普通 LRC 为 LINE，方括号/标准增强 LRC
  可解析为 WORD。

完整适配方法与后续播放器模板见：
`docs/4.0/PLAYER-ADAPTATION-REFERENCE-SALT.md`。

## 运行时与发布路径

- Salt 仅保留 Root `debug` / `release` 构建，入口调用 `notifyXposedHookActive()`。
- Root debug 读取 module-owned 配置。Provider APK 内置默认关闭的 Debug 页面；切换后重启 Salt，
  `[CLL] DEBUG` 同时进入 logcat 与 LSPosed framework log。4.0 全系列不适配 NPatch。
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
```

Root 定向测试与 Debug 构建通过。Salt NPatch build type 已在最终阻断结论后删除。

最终 APK：

| Variant | 运行模式 | 状态 |
|---|---|---|
| debug | Root / LSPosed | 保留，真机通过 |
| release | Root / LSPosed | 保留 |

Root APK package 为 `io.github.andrealtb.coloroslyrics.provider.salt`，versionCode=5、
versionName=1.0.4。解包扫描未发现 `LyriconFactory`、`LyriconProvider`、
`SystemUiBroadcastSender`、`EXTERNAL_LYRIC_DIRECT_V4` 或 `player.setSong`。

## NPatch 历史取证（不再复用）

- `salt-phase2-npatch-01.txt`：安全门绕过成功，但宿主 marker 不可见，运行模式为 UNKNOWN。
- `salt-phase2-npatch-02.txt`：运行模式已修复为 NPATCH_EMBEDDED，但 publisher 发现立即以
  `IllegalStateException` 失败，没有安装最终歌词发布 Hook，也没有 `LYRIC_INFO_PUBLISHED`。
- 已尝试签名 High bypass、两个 `Invalid App` 门限定绕过及显式运行模式信号；继续适配需要
  扩大 NPatch 宿主/loader/DexKit 特例，超出 Salt Provider 的可维护边界。
- `salt-phase2-npatch-03` 至 `08` 依次验证了外层/运行时 APK误选、NPatch code-cache、
  ClassLoader resource 与 `AssetManager.open("npatch/origin.apk")`。最终日志为
  `FileNotFoundException: npatch/origin.apk`。
- NPatch loader 本身可通过其私有原始 ClassLoader提取 origin APK，但嵌入 Provider 的公开
  Context/ClassLoader/AssetManager 均不可访问；继续需要耦合 NPatch 私有字段、缓存或维护
  loader 分支，超出可维护边界。
- 结论：Salt 的 NPatch 尝试已停止。4.0 全系列不再进行 NPatch 资格评估、重打包、嵌入实现或交付；
  Salt 专属 variant、安全门绕过、origin resolver 与通用 NPatch 基础设施均已删除。
