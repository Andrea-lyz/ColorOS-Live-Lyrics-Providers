# 4.0 v5 Provider 仓库最终清理报告

## 备份

删除前已建立双备份：

- 工作树源码快照：
  `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\backups\ColorOS-Live-Lyrics-Providers-pre-cleanup-20260828-164204\working-tree`
- 完整 Git 历史 bundle：
  `D:\Users\Andrea-TB\Desktop\锁屏岛歌词\backups\ColorOS-Live-Lyrics-Providers-pre-cleanup-20260828-164204\repository.bundle`

快照包含 904 个源文件、约 9.16 MB；bundle 已通过 `git bundle verify`，包含
`4.0`、`master` 和 `HEAD`。

## 删除内容

不属于最终 v5 矩阵或已被 v5 替代的 application：

- `:163-music`
- `:salt-player-music`
- `:music-free`
- `:gramophone`
- `:symfonium`
- `:qishui-music`（在汽水设备收口时已先行删除）

v5 无引用的旧 share：

- `:share:qrckit`
- `:share:krckit`
- `:share:cloudlyric`

其他旧仓库遗留：

- `COLOROS-LIVE-LYRICS-BRIDGE-ADAPTER.md`
- 空模板 `.github/FUNDING.yml`
- Lyricon Provider runtime 的版本目录项
- retained share 中 33 个未引用文件，包括 `ExternalLyricV4Protocol`、
  `SystemUiBroadcastSender`、旧 bridge policy/diagnostics/屏幕监听和对应测试
- 自动挂到每个 `assemble` 后的旧 `build/all-apks` 复制/ZIP 任务

## 保留内容

最终 12 个可安装 v5 Provider：

`:player-salt`、`:player-cone`、`:kuwo-music`、`:player-lx`、
`:player-poweramp`、`:player-metrolist`、`:player-kugou`、`:player-qq`、
`:player-netease`、`:player-apple`、`:player-spotify`、`:player-qishui`。

仍被 v5 使用的兼容 helper：

- `:share:extensions-kt`
- `:share:extensions-android`
- `:share:lrckit`
- `:share:yrckit`

其中 `io.github.proify.lyricon.lyric:model` 仅保留为 KuWo/NetEase 的兼容 DTO；
不存在 `LyriconFactory`、可安装 Lyricon Provider 或词幕挂载。

`LICENSE`、`NOTICE`、源码头和迁移报告保留历史来源与 Apache-2.0 署名。

## 构建验证

新增根任务：

```powershell
.\gradlew.bat assembleV5MatrixDebug
.\gradlew.bat assembleV5MatrixRelease
```

执行 `assembleV5MatrixDebug` 成功：

- 12 个矩阵 module 全部生成 debug APK。
- retained share 瘦身后的最终 Gradle `BUILD SUCCESSFUL`，耗时 21 秒。
- 535 个 actionable tasks：27 executed、1 from cache、507 up-to-date。

| Module | Debug APK SHA-256 |
|---|---|
| `player-salt` | `30D8F9F7F92BEC03ABD9285FA4F69596B680A6BBF6F0B99049F2F7DE6CDC6C1A` |
| `player-cone` | `F4A350DE651EAA0D9A9044FEE9958554E4318D417908C6A009BCFF2F1297F774` |
| `kuwo-music` | `4D96D20F6625EECF48B345913A4406A3EE0CABA16CEFA7482863E4EF2985C5BE` |
| `player-lx` | `5A51A6B6DF97360BA075B0B144162171F9746AF946E5289EE5E24C691B4A54A7` |
| `player-poweramp` | `EF410A10A6C5B98DBEB236FA35B8DD9EE57B337CAD4A345F4BF45898DAEBDCE8` |
| `player-metrolist` | `24AE1054367E99CD668A577A3F444F546D523CFCF84A5910D4B2920CD9959F65` |
| `player-kugou` | `62827202D6F5A4E80285B32FE1533A5C9C44E95DAE4447A5070A8846B387222E` |
| `player-qq` | `2973307B76E4B519D26D16E02343D98B551286C1902D1C69651CCBF22B4DDED5` |
| `player-netease` | `7F83B1878BC3BE149A352143A902940D02B45BD0D026585263699752D8DEEAA7` |
| `player-apple` | `2727DE587A3B0AD1FAAAACD05CE6057E230CA45B542BEA6A1C67386306CEFE4E` |
| `player-spotify` | `9E4F638C47608CEAC31FC4C20EA6C5D70672D51FE25AEFC1BF5913CAC83893BD` |
| `player-qishui` | `00B08659407C8CBDCFCC299813F261A53D617E017E885973BEB62245C73B4641` |

## 发布边界

GitHub Actions 只调用显式 v5 矩阵任务，并要求收集结果恰好为 12 个 APK。
Bundle 名称已改为 `ColorOS-Live-Lyrics-Providers-<buildType>.zip`，不再使用
`LyricProvider` 品牌或旧 module 集合。

本机 `build/all-apks/debug` 只剩一个空的 gitignored 目录，但被外部进程持有工作目录
句柄，停止 Gradle daemon 后仍无法删除。根构建已不再创建或读取该目录，它不会进入 Git
或未来 GitHub 仓库。
