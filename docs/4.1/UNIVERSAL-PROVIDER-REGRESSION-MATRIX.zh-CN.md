# Universal Provider 回归矩阵

## 自动化门禁

| 项目 | 命令 | 结果 |
|---|---|---|
| 通用 Provider 单元测试 | `:universal-provider:testDebugUnitTest` | 通过 |
| v5 Provider 全矩阵 | `testV5Matrix` | 通过，455 tasks |
| 通用 Provider Debug 构建 | `:universal-provider:assembleDebug` | 通过 |

## 真机矩阵

设备：ColorOS 16 / Android 16，Salt Music，`com.salt.music`。

| 场景 | 预期 | 当前证据 |
|---|---|---|
| 冷启动，首曲，蓝牙歌词关闭 | 首曲直接出现歌词 | 已验证 |
| 冷启动，首曲，蓝牙歌词开启 | 不切歌，等待取词后出现歌词 | 已验证 |
| 蓝牙歌词滚动 | title 变化不增加 generation | 已验证，generation 保持不变 |
| 蓝牙歌词滚动 | 不重复发起网络请求 | 已验证，同 generation 无重复请求 |
| 暂停后恢复 | 保持同一 track identity 和 lyricInfo | 待设备复测 |
| 同曲 seek/repeat | 不误判为换歌 | 待设备复测 |
| 真实切歌 | generation 增加并加载新歌词 | 已验证，artist 变化和 position reset 可触发 |
| 快速连续切歌 | 旧请求不得覆盖新曲 | 待设备复测 |
| 缓存命中 | 不联网并直接附加 lyricInfo | 已验证 |
| 网络失败 | 保持无歌词并按退避重试 | 已验证日志路径，需补设备复测 |
| 手动绑定歌词 | 切歌返回后仍使用绑定结果 | 待设备复测 |
| 纯音乐/无歌词 | 保持无歌词大封面 | 待设备复测 |
| B 站/视频应用未勾选 | 不注入 lyricInfo | 已有白名单门禁，待复测 |
| 关闭“优先使用逐字时间轴”，保留 rawLyric | 发布逐行 `rawLyric`，Bridge 渲染逐行 + 翻译，不回退官方渲染 | 单元测试覆盖 payload；待设备复测 |
| 处理开关关闭后重新开启 | 缓存保存完整 payload，下一首起恢复逐字/翻译，无需清理缓存 | 待设备复测 |
| 取词完成时 App 在后台或锁屏 | 近期歌词缓存行更新为最终状态，不停留在“正在获取” | 2026-09-25 设备复现：App 被冻结或未运行时收不到快照广播，列表不更新；已改为 system_server 维护最近行，待设备复测 |
| 安装新版并重启后，在 App 未运行期间连续播放多首，再打开 App | 列表按最新在前列出这些歌曲及最终状态；旧版本留下的“正在获取”行在收到 system_server 列表后被清除 | 单元测试覆盖合并与清理；待设备复测 |
| UAPP 类粗粒度进度，逐字开启 | 约 7 s 后出现 `CLOCK_QUALITY_CHANGED verdict=COARSE`，当前曲目改为逐行 + 翻译，首页提示“已自动逐行” | 单元测试覆盖判定；待设备复测 |
| 关闭“进度粒度粗时自动逐行” | UAPP 恢复逐字（下一首起），`RAW_LYRIC_POLICY clockFallback=false` | 待设备复测 |
| Salt 等事件驱动播放器 | 不出现 `COARSE` 判定，逐字不受影响 | 待设备复测 |
| UAPP 连续快切 5–10 次 | 每次切歌 generation 只增 1，不出现旧标题歌词；`IDENTITY_GATE` 记录被拦截的撕裂 payload | 单元测试覆盖两种到达顺序；待设备复测 |
| UAPP 类粗粒度进度 + 当前 Bridge（行号时钟对齐） | 行边界不回跳，官方行号与逐字填充同时切换；Bridge 日志含 `Hooked lyric position controller`，renderer debug 下 `NATIVE_CLOCK_ALIGNED` 的 `\|deltaMs\|` ≤ 600 | Bridge 单元测试含粗粒度锚点模拟；2026-09-25 设备日志（自动逐行开启）：`controllerHooked=true`，`deltaMs` −174..+251，行号采样单调 |
| 上一行场景下关闭“进度粒度粗时自动逐行” | 逐字模式下行切换不回跳，高亮行与逐字填充一致（不闪回上一行满填充） | 待设备复测 |

## 已知缺陷：蓝牙/车载歌词冲突

部分播放器会把当前歌词行持续写入 `MediaMetadata.TITLE`，将标准 MediaSession 的歌曲标题字段当作蓝牙/车载歌词显示通道。通用 Provider 无法在首次播放且没有稳定 media ID、queue ID、media URI 或历史缓存时可靠恢复真实歌名。

发布说明必须明确提醒：**使用通用 Provider 时，请关闭播放器内的蓝牙歌词、车载歌词或类似功能。两者同时启用可能导致首曲无歌词、识别延迟、错误匹配或频繁切换歌词。**

该问题已按用户决定标记为已知缺陷，不作为当前发布阻断项；后续除非重新明确排期，不继续扩展播放器特定的蓝牙歌词识别规则。

## 设备复测命令

```powershell
.\scripts\capture-lyrics-log.ps1 -Provider all
```

每个场景单独抓取一次，日志至少应包含 `SET_METADATA_HOOK_ENTERED`、`METADATA_OBSERVED_BEFORE`、`LYRIC_INFO_ATTACHED`、`TRACK_CHANGED`、`FETCH_STARTED` 和 `FETCH_FINISHED` 中与场景对应的事件；粗粒度进度与快切场景另需 `CLOCK_QUALITY_CHANGED`、`IDENTITY_GATE` 和 `RAW_LYRIC_POLICY`。日志不得包含完整歌词、原始 media ID 或私人路径。
