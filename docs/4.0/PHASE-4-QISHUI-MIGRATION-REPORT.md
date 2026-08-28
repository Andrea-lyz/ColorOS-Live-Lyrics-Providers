# Phase 4：汽水音乐 Root/LSPosed v5 Provider 迁移

## 状态

`DEVICE_VALIDATED_COMPLETE`

目标模块为 `:player-qishui`，`applicationId` / namespace 为
`io.github.andrealtb.coloroslyrics.provider.qishui`，宿主仅
`com.luna.music` 主进程。首轮真机已确认整首逐字、翻译 payload、metadata artwork
与主 MediaSession 写入链可用。第三轮真机确认 VIP 试听定位、实时行焦点、逐字进度和
翻译按钮生命周期全部收口；汽水 v5 适配完成。

## 静态依据

- 样本：`PlayerSource/qishui/luna_qishui_20.7.0.apk`，包名 `com.luna.music`。
- `CoreRemoteControl#update(RemoteControlContext, MediaMetadataCompat.Builder)` 从
  `RemoteControlContext#getA()` 取得 `IPlayable`，写入稳定 `MEDIA_ID` 后调用主
  `MediaSessionCompat#setMetadata`。
- 该主 metadata 链没有官方 ColorOS `lyricInfo`，因此本模块构造
  `source=com.luna.music-v5`。
- `RemoteControlContext` 的 Kotlin metadata 名为 `getPlayable`，20.7.0 运行时方法名是
  `getA`。v5 解析器同时保留 `getA`、旧版混淆名和 Kotlin getter 候选，不能把单一混淆名
  当作跨版本契约。

## 实现边界

1. `QishuiOfficialLyricsHook` 在 `CoreRemoteControl#update` 返回后读取当前
   `IPlayable` 已加载的 `TrackLyric`。这是第一优先级，不由 Provider 发起网络请求。
2. `QishuiInternalLyricsDecoder` 读取原文、`lang_translations`、歌曲 ID/标题/歌手/
   时长；罗马音、拼音、transliteration 和 pronunciation key 永不进入翻译 lane。
3. `QishuiKtvLyricParser` 把 `[lineBegin,duration]` 与
   `<wordOffset,wordDuration,flag>` 转为绝对毫秒逐字时间；普通 LRC 走 `:parser-lrc`。
4. 宿主内部 `NetCacheLoader` 和 `recent_played_*.db` / `history_*.db` 仅作为
   `TrackLyric` 尚未就绪时的有界回退。最多八次尝试，负缓存 15 秒。
5. 平台 `MEDIA_ID` 是 track authority。所有内部回调和缓存结果都校验
   `mediaId + generation + token`，切歌立即失效旧结果。
6. `QishuiNativePublisher` 通过空 typed `MediaMetadata.Builder` 复制宿主字段，
   保留 artwork/URI/rating/long/text；HARDWARE 或超过 240px 的现有 bitmap
   仅做 binder-safe Canvas 重绘，不下载、不快照、不发明封面。
7. `NativeLyricInfoPublisher` 生成标准 `lyric`、`rawLyric`、
   `translationLyric`、`trackKey` 与 `sessionGeneration`，写回发布当前
   `MEDIA_ID` 的主 `MediaSession`。同曲宿主 metadata 重建时 replay。
8. 翻译按钮仅在当前 generation 的歌词确有 `secondary` 翻译行时，通过
   `PlaybackStateTranslationToggle` 加入公开 CustomAction；切歌先隐藏，翻译就绪后
   调用宿主 `CoreRemoteControl#updatePlaybackState` 以实时重绑 Rule0。
9. 歌词首次提交后延迟 700ms 调用同一个宿主 `updatePlaybackState`，让 VIP 试听自动
   seek 后的 `IPlayerController#getPlaybackTime` 重新进入 MediaSession；Provider
   不猜测、不构造试听起点。

## 明确未迁移

- `LyriconFactory`、`player.setSong`、`player.setPosition`
- Provider → Bridge v4 广播及外部播放状态
- NPatch、重签名、loader、SystemUI 绕过
- native 防修改补丁、LSPosed 设置修改、`libart.so` 清理

真机等价验证已完成，旧 `:qishui-music` 与 Bridge
`lyricprovider/qishui-music` source 已删除。

## 本地验证

- `:player-qishui:compileDebugKotlin`：通过。
- `:player-qishui:assembleDebug -x cleanAllApks`：通过，独立 debug APK 已生成。
  首次组合命令中的仓库全局 `cleanAllApks` 曾因 `build/all-apks/debug` 被占用失败，
  不影响该模块编译结果。
- APK：`player-qishui/build/outputs/apk/debug/player-qishui-debug.apk`，
  SHA-256 `0B7D8F7EDE783BD089BB9B3F98F623A94F837B9F8FD3B1F13BBA84013788B365`。
- 双修 APK 已通过 `adb install -r` 覆盖安装到
  `192.168.2.201:6666`（PJZ110）；未启动播放器、未抓取新日志。
- Bridge `:app:assembleDebug`：通过；新 Provider package query 与翻译设置入口已编译。
- `:player-qishui:compileDebugUnitTestKotlin`：通过。
- `:player-qishui:testDebugUnitTest`：当前 Gradle test worker 未把
  `built_in_kotlinc/debugUnitTest` 加入运行 classpath，所有测试类报
  `ClassNotFoundException`；同一命令对既有 `:player-spotify` 以及 Bridge
  `:app:testDebugUnitTest` 也复现，属于仓库级测试运行器问题，不是汽水测试断言失败。

## 首轮真机日志收口与修复

日志：`logs/lyrics-log-20260828-154634.txt`。

1. `失眠 - Suki刘舒妤` 切入时，SystemUI 先收到宿主 seek 前位置 `5ms`，随后绑定新曲
   （日志 1504–1507）；歌词模型已切换，但 1507 后首帧仍以接近零的位置渲染，直到暂停时
   宿主重新发布 `76288ms`（日志 2185），恢复播放后才正确定位（日志 2191）。
   静态源码确认 `CoreRemoteControl#updatePlaybackState` 每次都从
   `IPlayerController#getPlaybackTime` 读取实时位置。修复因此只做一次代际内延迟宿主刷新，
   不把首句时间或固定偏移伪装成播放位置。
2. 翻译按钮的根因是 Provider 无条件注入 action：同一日志中
   `translationChars=0` 时 Rule0 仍始终 `count=1`（1485–1486、1505–1506、
   2187–2188），而有翻译的 payload 也是同一个 action（20–22）。
   现在 track generation 变化时翻译数立即归零；只有当前 publication 的翻译行数大于零
   才注入 action，并在可用性变化后复用宿主状态刷新使按钮及时出现或移除。

## 第二轮真机日志收口与修复

日志：`logs/lyrics-log-20260828-161038.txt`。

1. 翻译按钮显隐已符合 payload：无翻译的 `失眠` 在切入时 Rule0
   `count=0, actions=[]`（日志 3321、4006–4007），说明 Provider 代际翻译门控生效。
2. VIP 实际位置已进入汽水/SystemUI：切入先收到 `6ms`（3318–3325），随后官方
   Recycler 的 `activeIndex` 已跳到试听区间的 12/13（3995、3999），说明延迟宿主
   `updatePlaybackState` 成功取得真实位置。
3. 异常只剩 Bridge 自绘时钟：自绘仍使用 `779ms / 1983ms / 3782ms`，并记录
   `activeIndex=0, scaleActiveIndex=-1, active=false, focused=false`
   （3993、3995、3998）。暂停后立刻变为 `78208ms` 且
   `active=true, focused=true`（4008），与用户观察一致。
4. 根因是 `shouldIgnoreStalePlaybackPositionAfterTrackReset`：track reset 后的保护窗
   会把“从接近零跳到试听进度”的 PLAYING 位置当作上一曲残留，尽管该 PlaybackState
   的 `lastPositionUpdateTime` 是 reset 之后由宿主新生成的。Bridge 现将同一 elapsed
   realtime 时钟域内、更新时间不早于 reset 起点的状态视为新曲权威位置；旧时间戳的
   真正陈旧状态仍继续被拒绝。
5. Bridge `:app:compileDebugUnitTestJavaWithJavac :app:assembleDebug` 通过。APK
   SHA-256 `A092F36991BD15D817F7271EDE95786D0113985BA4109AE9F52C5596A2CAF03B`，
   已覆盖安装到 PJZ110；未启动播放器、未抓取第三轮日志。

## 第三轮真机最终收口

日志：`logs/lyrics-log-20260828-162349.txt`。

1. VIP 新鲜位置在播放态直接被 Bridge 接受：`storedPosition=70107,
   computedPosition=70107`（日志 8），无需暂停。
2. 自绘逐字与官方 Recycler 同步：`activeIndex=12,
   scaleActiveIndex=12`（15–16），随后行 12/14/15 均
   `active=true, focused=true` 且 `WORD_TIMED`（22、25、32）。
3. 无翻译曲目继续保持 `count=0, actions=[]` 和
   `translationChars=0`（9–11、27–28），翻译按钮生命周期无回归。
4. 用户确认“完美”，汽水 Root/LSPosed v5 设备验证完成。至此全 Provider v5
   适配矩阵全部完成。
5. 删除旧 module/source 后两仓库重新构建通过；最终 Bridge debug APK SHA-256
   `D8DEF95C7836233590B07401D082A4D05B3EE919267D9FC30B1250B09248A0F9`，
   已覆盖安装到 PJZ110。

## 最终结论

`QISHUI_ROOT_LSPOSED_V5_DEVICE_VALIDATED_COMPLETE`
