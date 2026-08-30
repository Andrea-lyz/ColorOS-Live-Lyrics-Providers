# 4.0 Provider 适配技术指南

[English](PROVIDER-ADAPTATION-GUIDE.md)

本文规定向 `ColorOS-Live-Lyrics-Providers` 新增播放器时的主要技术路线。Provider 是运行
在目标播放器进程中的独立 Root / LSPosed 模块，从播放器自己的 MediaSession 发布标准
`MediaMetadata["lyricInfo"]`。

```text
播放器私有歌词源
        ↓
player module：进程门禁 → 曲目 generation → 歌词解码
        ↓
官方 payload 追加 encoder 或 NativeLyricInfoPublisher
        ↓
播放器自己的 MediaMetadata["lyricInfo"]
        ↓
ColorOS SystemUI（Bridge 可选）
```

Provider 不调用 Bridge 类、不发送 Bridge transport，也不假设设备已安装 Bridge。

## 1. 先选择发布模式

写 hook 前必须明确采用哪一种模式。

### 官方 payload 追加

播放器已经发布合法 `lyricInfo` 时，hook 最窄的 writer/capture 边界。保留官方 `id`、
`songId`、`lyricType`、`lyric`、`noLyric` 等字段，只追加缺失 lane 和身份/代次诊断字段。

现有例子：

- `QqOfficialLyricInfoEncoder`
- `KuGouOfficialLyricInfoEncoder`
- `KuWoOfficialLyricInfoEncoder`
- NetEase `OFFICIAL_APPEND`

不能只为“代码统一”把已经工作的官方 payload 换成 generic constructed schema。

### 自行构造 payload

播放器没有可用官方 payload 时，使用 `NativeLyricInfoPublisher`。将权威宿主歌词模型转换为
`TrackIdentity` + `List<RichLyricLine>`，通过事务式发布器生成标准 JSON。

现有 Apple、LX、Poweramp、Metrolist、Spotify、QiShui、Salt、Cone 采用该方向；NetEase
9.0.40 选择 constructed profile，而当前官方版走 append。

## 2. Module 骨架

每个可安装 module 至少包含：

```text
player-<name>/
  build.gradle.kts
  proguard-rules.pro
  src/main/AndroidManifest.xml
  src/main/assets/xposed_init
  src/main/res/values/arrays.xml
  src/main/kotlin/.../HookEntry.kt
  src/test/kotlin/...
```

applicationId 使用：

```text
io.github.andrealtb.coloroslyrics.provider.<player>
```

要求：

- `minSdk=27`，compile/target SDK 跟随根工程，Java/Kotlin 17 bytecode；
- `xposedmodule=true`、`xposedminversion=93`、`xposedsharedprefs=true`；
- 导出的独立调试设置 launcher Activity，读取本模块 preferences；
- `@array/xposed_scope` 只包含支持的宿主包；
- 复用目标播放器 launcher 图标，不拿其他 Provider 图标占位；
- release 签名统一读取矩阵构建已约定的环境变量。

把 module 加入 `settings.gradle.kts`、根 `v5ProviderModules` 和
`release/v5-provider-matrix.json`。机器契约必须记录 applicationId、内部版本、规范资产
名、scope、进程策略证据和已验证宿主版本。

## 3. 进程与 MediaSession 门禁

Manifest scope 不等于进程门禁。必须确认真实播放 MediaSession 位于哪个进程。

现有显式策略：

- QQ：`com.tencent.qqmusic:QQPlayerService`；
- 酷狗标准版/概念版：只 hook `:support` / `.support`；
- Spotify、QiShui：只 hook 主进程；
- NetEase：由 package + 主进程/`:play` 组合选择运行 profile。

拒绝 push、download、cast、preview、message、remote 和其他辅助会话。播放器确实跨多个
进程时，把判断收口到具名 `<Player>ProcessPolicy` 或 `<Player>RuntimeProfile`，并测试
每个接受/拒绝分支。

多个产品共用包名时不能只按 package 路由，必须使用已有证据的进程/结构 profile。

## 4. Track identity 与 generation

每个真实播放器/会话 owner 使用一份 `TrackIdentity` 与 `TrackGenerationPolicy`。

身份优先：

1. 宿主稳定 media ID；
2. 歌名 + 歌手 + 时长；
3. 具名播放器专属身份派生策略。

规则：

- 真实换曲只递增一次 generation；
- 同曲 metadata 补全只合并，不递增；
- 队列预加载不能成为当前曲权威；
- 蓝牙/车载歌词的 title projection 不得被识别成新歌；
- 异步任务捕获 ID + generation，发布前再次核对；
- 新 generation 清理上一首自有 pending/replay 状态。

禁止异步 HTTP/JNI/数据库结果回来后“当前是谁就写给谁”。

## 5. 捕获最窄的权威歌词源

优先级：

1. 播放器最终 UI/播放 writer 已解码的歌词模型；
2. 播放器官方 `lyricInfo` writer/capture 对象；
3. 播放器拥有的本地 sidecar 或内嵌标签；
4. 只有宿主不存在权威数据时才建立独立取词路径。

hook 发现可使用已知名称、结构反射、KavaRef 或 DexKit，但发现与运行时访问必须分开：

- DexKit 发现未知/混淆目标；
- 具名 resolver 处理零/唯一/多候选；
- KavaRef 或受控反射访问已确认成员；
- 句柄按 ClassLoader/宿主版本缓存，并在适当时释放。

不能静默取第一个方法。零候选和歧义候选必须是不同诊断结果。

## 6. 归一到 `RichLyricLine`

中立模型：

```kotlin
RichLyricLine(
    begin = absoluteLineStartMs,
    end = absoluteLineEndMs,
    text = primaryText,
    words = primaryWords,
    secondary = translationText,
    secondaryWords = translationWords
)
```

按歌词源使用共享 parser：

- `parser-lrc`
- `parser-qrc`
- `parser-yrc`
- `parser-krc`
- `parser-ttml`

时间与 lane 规则：

- 行/字时间全部是绝对媒体毫秒；
- 行按时间排序，逐字时间不得倒退；
- 重复时间行默认保留，除非播放器契约能证明应合并；
- 推广/无效行在翻译对齐前删除；
- 每条翻译只消费一次并只对齐一条主句；
- 罗马音、注音、音译绝不进入 `secondary`；
- 宿主把一个拉丁词拆成无空格音节时，先合并显示词；
- 逐行源保持逐行，不伪造卡拉 OK 时间。

## 7. 事务式编码与发布

constructed payload 在全部门禁通过后调用
`NativeLyricInfoPublisher.publishToPlatformMetadata`。它负责：

- original metadata 非空、track 非空、歌词行非空；
- 宿主包相等；
- generation 和当前曲身份有效；
- lyric 字段长度限制；
- 完整候选 metadata Parcel 测量；
- 拒绝路径不修改传入 builder。

默认上限为歌词字段 1,500,000 字符、完整 metadata Parcel 512 KiB。超限只返回
`PAYLOAD_TOO_LARGE`，原 metadata 继续 fail-open。

官方 append 模式保留官方字段，只在有合法时间轴时追加：

- `rawLyric`
- `translationLyric`
- `provider`
- `source`
- `sessionGeneration`
- 可选稳定 `trackKey`

不得构造 Bridge envelope。

## 8. Metadata 与封面安全

进入 `MediaSession#setMetadata` 的宿主 metadata 是权威数据。按类型复制全部宿主字段，
包括未知键、rating、duration、ID、封面 URI 与 bitmap。

受影响 ColorOS 上避免 `MediaMetadata.Builder(existing)`，改用空 typed Builder。封面规则：

- 保留合理宿主 bitmap；
- 只有 HARDWARE 或过大 bitmap 需要 Binder 安全时才重绘为 software `ARGB_8888`；
- 宿主尚未解码 bitmap 时保留 URI-only 首帧；
- pending `lyricInfo` 叠加到 incoming metadata，不用旧快照回放覆盖；
- 不联网补封面、不伪造封面、不恢复其他歌曲封面。

忽略 cast/辅助会话。payload 不能移动到当前曲身份不同的 MediaSession。

## 9. PlaybackState 与翻译按钮

保留宿主 PlaybackState 语义，不能为唤醒 SystemUI 伪造 PLAYING、position、speed 或
update time。

支持公共翻译按钮时使用 `PlaybackStateTranslationToggle`：

```text
io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION
```

它通过空 Builder 复制宿主状态，保留 actions/extras，避免重复并返回新实例，促使 ColorOS
重新绑定。官方 action row 所有权不同的播放器不应注入；对应模块可以保留已有证据的
Bridge 收藏槽显示路线。

## 10. Debug 与隐私

每个模块使用一份 `ProviderId` 和自有 debug preferences。模块设置页先用
`MODE_WORLD_READABLE` 打开 preferences，供播放器进程读取 LSPosed 导出配置。开关默认
关闭，且不能改变 hook、身份、generation、解析或发布行为。

结构化事件格式：

```text
[CLL] level=INFO component=provider/<player> area=<area> event=<event>
```

开启时将关键事件同时写入 logcat 与 Xposed framework sink。使用稳定事件名，例如
`PROCESS_READY`、`TRACK_BOUND`、`LYRIC_CAPTURED`、`LYRIC_INFO_PUBLISHED` 和分类拒绝原因。

禁止记录完整歌词、authorization/client token、cookie、URL query、原始稳定 media ID
和私人本地路径。使用 `DiagnosticHasher`、`SensitiveFieldRedactor` 与节流。

## 11. 测试

每个 module 至少覆盖：

- package/process 接受与拒绝；
- 同曲与真实换曲；
- generation 失效和旧结果拒绝；
- parser 时间/lane 分类；
- 翻译与 pronunciation 区分；
- 官方字段保留或 constructed JSON 结构；
- metadata copy 与封面策略；
- pending/replay 取消；
- 使用公共 action 时保留宿主状态；
- 诊断脱敏。

先跑定向模块，再跑完整矩阵：

```powershell
.\gradlew.bat :player-<name>:testDebugUnitTest :player-<name>:assembleDebug
.\gradlew.bat testV5Matrix assembleV5MatrixDebug
```

release/R8 由签名矩阵 workflow 验证。缺少签名环境时必须在配置阶段失败，release 不得
回退 debug 签名。

## 12. 真机验收梯子

先在没有 Bridge 时验证 Provider，再安装 Bridge：

1. module/process ready，debug 配置已应用；
2. 一首权威歌曲只绑定一个 generation；
3. 歌词捕获并分类为 LINE/WORD/NO_LYRIC；
4. 原生 `lyricInfo` 写入正确 MediaSession；
5. 只装 Provider 时 SystemUI 官方锁屏歌词可见；
6. 安装 Bridge 后只增加渲染/AOD/翻译能力，没有重复提交；
7. 暂停/恢复、seek、快速切歌、同曲重播、锁屏/解锁和 AOD；
8. 封面、通知栏控件、蓝牙/车机 metadata 与 action row 保持原生；
9. 连续快速切三首后不存在旧结果；
10. debug 关闭无高频 trace，开启后结构化且脱敏。

迁移报告记录宿主 APK 版本/SHA、package/process、设备/ROM/SystemUI/LSPosed 版本、
Provider commit/APK 哈希和用户最终确认结果。

## 13. 发布契约

Provider 进入套件前必须：

- 加入 `release/v5-provider-matrix.json`；
- 通过 `scripts/validate-v5-release-contract.ps1`；
- 保持根 module 数量精确；
- 使用 `ColorOS-Live-Lyrics-Provider-<Name>-v<suite>.apk` 规范资产名；
- 通过 `testV5Matrix`、release/R8、包名/版本/scope/证书检查与真机验收。

当前 4.0 Release 精确包含 12 个 Provider APK。新增第 13 个 module 是显式发布契约修改，
不能依赖 workflow 自动扫描目录。

## 14. 参考资料

- [Salt 适配参考](PLAYER-ADAPTATION-REFERENCE-SALT.md)
- [v5 迁移矩阵](PHASE-0-MIGRATION-MAP.md)
- [最终仓库清理](REPOSITORY-CLEANUP-REPORT.md)
- 各播放器 `PHASE-4-*-MIGRATION-REPORT.md`
- [播放器主动发布 `lyricInfo` 协议](https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Bridge/blob/4.0/docs/PLAYER_INTEGRATION.zh-CN.md)
