# M1 Salt 启动回归：早期 Application 诊断 NPE

日期：2026-11-13；设备确认更新：2026-11-14。状态：**真实设备根因已证实；最小修复及本地回归通过；修正版已由用户实机确认专用链路恢复供词**。

## 已纠正的判断

用户关闭 Universal 对 Salt 的取词后，全部歌曲无词。此前“实际播放大部分正常”的结果不能用于验收专用 Salt Provider；实际供词者包含 Universal。Bridge 自身安装/注入 buildId 匹配仍成立，但不证明专用 Provider 工作。原 M1 Salt Debug 候选已撤回，不能继续推荐。

## 1. 同一窗口中的证据

用户明确授权使用原有 ADB 实时脚本，随后单独授权只读 Root 获取 LSPosed 模块/错误日志。没有安装、播放、切歌、清数据、改 scope 或重启设备；用户自行操作。抓取停止后全部 8 个临时日志标签已恢复原来的空值，终端已关闭。

- 专用 Salt 在多个新进程均有 MODULE_LOADED（原候选 sourceDigest=e9b97b58463c0a2a5b22993071e11a2966860eadade8a38c5ba446c82cc8c61b）和 PROCESS_ACCEPTED，不是“模块 APK 未加载/进程未放行”。
- 普通实时 logcat 没显示后续业务安装，单凭缺失不能判定原因。读取同窗 LSPosed 自身日志后取得 ProtectiveHooker 的准确异常。
- [最小 Salt 异常证据](<../../artifacts/m1-salt-bootstrap-fix-20261113/salt-attach-exception.txt>)：10:02:42，Application.attach 的 after 内，宿主重写 getApplicationContext 抛出 NPE；栈指向 ProviderRuntimeDiagnostics.register:57，再到 ProviderApplicationBootstrap.bootstrapHost:82。没有把其他模块的异常算到本问题。

## 2. 根因与最小修复

M1 新诊断在业务 Hook 安装前执行 context.getApplicationContext。传入对象已经是实际宿主 Application，但 Salt 的 override 依赖 onCreate 后才可用的状态，attach 阶段会抛错。该调用不在诊断注册的 try 块内，bootstrap 外围也未隔离；hostBootstrapped 又已置位，异常被框架保护捕获后，取词 installer 未执行。这是本轮诊断引入的启动回归，不是缺歌词、网络延迟或 Bridge 渲染配置。

修改范围：

1. [ProviderRuntimeDiagnostics](<../provider-runtime-diagnostics/src/main/java/io/github/andrealtb/coloroslyrics/provider/runtimediagnostics/ProviderRuntimeDiagnostics.java>)：对 Application 直接使用其自身，**不调用宿主 override getter**。非 Application Context 读取 applicationContext 失败可使用已提供的 Context；包名读取到注册整体放入 RuntimeException/LinkageError 边界。
2. [ProviderApplicationBootstrap](<../provider-hook-api102/src/main/kotlin/io/github/andrealtb/coloroslyrics/provider/hook102/ProviderApplicationBootstrap.kt>)：业务入口外围再次隔离可选诊断注册；异常只记录结构化类型，不阻止 installer，不打印私人错误 message。保持原 Application.attach 时机与实际 Application 传递，不增加生命周期 fallback、业务重试或 deoptimize。
3. [生命周期回归](<../provider-runtime-diagnostics/src/test/java/io/github/andrealtb/coloroslyrics/provider/runtimediagnostics/RuntimeDiagnosticsLifecycleTest.java>) 覆盖真实形状的早期 Application：getter 调用就 NPE，断言完全不调用它仍可注册；畸形可选上下文也不外泄。
4. [完整 bootstrap 回归](<../provider-hook-api102/src/test/kotlin/io/github/andrealtb/coloroslyrics/provider/hook102/ProviderApplicationBootstrapTest.kt>) 注入诊断 NPE/链接错误，断言正常 installer 可达，重复 attach 只安装一次。新增 Robolectric 仅为 testImplementation，不进入 APK。

没有改 Bridge、生产 scope、API/入口格式、偏好通道、取词逻辑、identity/generation、metadata typed-copy 或 PlaybackState 策略。修复落在共享层，完整矩阵已验证；不是为 Salt 另写私有分支。

## 3. 实际验证

- [修复前](<../../artifacts/m1-salt-bootstrap-fix-20261113/regression-before-fix.log>)：新增两项准确失败，分别是 NPE 与可选上下文异常外泄。
- [修复后定向](<../../artifacts/m1-salt-bootstrap-fix-20261113/regression-after-fix-1.log>)：诊断、共享 Hook、Salt 测试与 Debug 构建通过。
- [完整矩阵 + Salt R8-only](<../../artifacts/m1-salt-bootstrap-fix-20261113/providers-full-regression.log>)：通过；[当前实际统计](<../../artifacts/m1-salt-bootstrap-fix-20261113/test-summary.json>) 900 tests / 0 failures / 0 errors / 0 skips。其中诊断库 23、共享 Hook 19、Salt 41。
- 705 个生产/构建输入已独立复算；sourceDigest=c8c288b5e1f412607c3dfb10cd0ce9fe31738f4fb19389c342a4495cb6ceb190，dirty。Root matrix 原哈希保持不变。
- [修正 Debug 候选](<../../artifacts/m1-salt-bootstrap-fix-20261113/Salt-Provider-v1.1.2-M1-attach-fix-debug.apk>)：包名 io.github.andrealtb.coloroslyrics.provider.salt，1.1.2 / 8，4,920,531 bytes，SHA-256 ECBE2EAC89597B8E71D5A33AA48349046776AF2ACDE03B033215785F8010C929。
- 原 Debug 证书 SHA-256 5A6FD43FC11B4C70FF12B6F82A7261C2ED9C4945692B3D818CA50BC64C8DD869 已核验。不是正式 Release；R8-only 未打包或签名正式 Release。

## 4. 必须单独验收专用 Salt

只替换 Salt，不需要重装 Bridge。Universal 对 Salt 保持关闭，其他配置不变。由用户正常覆盖安装同证书候选、完全结束并重开 Salt，让新进程加载修复，不清数据、不用最近任务划走替代结束后台音乐服务。

先在 Salt 模块设置反复刷新“本模块运行诊断”：应不再因 attach getter 异常中断，观测 buildId 应含新 digest c8c288b5…，正常应 MATCH，安装器阶段应可观测。随后正常播放/切歌确认专用 lyricInfo 链路，并抓同窗日志。

本地测试不能替代这一设备验收。如果接下来仍无词，要沿真实 HOOK_INSTALL_SUMMARY / LYRIC_INFO_PUBLISHED 等事件继续定位；不能因为修掉这次 NPE 就假定播放器后续所有 Hook 都正常。

## 5. 设备确认（2026-11-14，用户提供）

用户在 Salt 模块设置反复刷新只读诊断，导出 JSON 显示：

- `installedBuildId` 与进程自报 `observedBuildId` **完全一致**，均为修正版 `c8c288b5…`，不再是空 `processes`。
- `installerStage=INSTALLER_RETURNED`：业务安装器已执行完毕（此前因 NPE 从未到达）。
- `configObservation=CONSUMED`，`api=102`，`framework=LSPosed / 2.2.1 (7941)`，`processEpoch=033728ad-cca9-4c3e-9248-086b8fd76b76`。
- 用户同时确认"歌词加载正常了"。

因此这次定位与修复在这一台 ColorOS 17 / LSPosed 102 设备上成立。仍然**不**代表：其他 Provider、其他机型、切歌交接窗口、正式 Release 签名与 R8 形态已通过。

补充：静态复核指出 installer 之前仍有一处未隔离的诊断调用（`ProviderDebugConfig.applyDiagnostics` → `configConsumed`），以及 installer 之后的 `installerReturned`。它们当前只写内存状态，**没有设备故障证据**，但属于同一 fail-open 承诺的残留缺口，应作为后续小修处理，不要因此否定已确认的本次修复。（2026-11-14 已收口，见第 6 节。）

## 6. 后续：剩余两处诊断 fail-open 收口（2026-11-14）

状态：**代码已改，本地回归通过；未打候选、未安装、未做设备验证**。

修改范围（只隔离诊断写入，配置解析结果、Hook 安装顺序与发布行为不变）：

1. [ProviderDebugConfig](<../provider-core/src/main/kotlin/io/github/andrealtb/coloroslyrics/provider/core/config/ProviderDebugConfig.kt>)：`applyDiagnostics` 中的 `configConsumed` 写入包进 `RuntimeException | LinkageError` 边界。该函数被 12 个播放器 Hooker（含 KuWo）在业务 Hook 安装前调用，所以这是共享层缺口，不只是 Salt。新增 `internal runtimeConfigRecorder` 仅作测试注入点，默认即 `ProviderRuntimeDiagnostics.configConsumed`；返回的 `ProviderDebugResolution`、`StructuredDiagnostics.configureForRuntime` 及其顺序未改。
2. [ProviderApplicationBootstrap](<../provider-hook-api102/src/main/kotlin/io/github/andrealtb/coloroslyrics/provider/hook102/ProviderApplicationBootstrap.kt>)：`installerReturned` 改经构造参数 `recordInstallerReturned`（默认即真实调用，与既有 `registerDiagnostics` 同形）并纳入同一边界；失败只记 `RUNTIME_DIAGNOSTIC_FAILED` 与异常类型，不记 message。`HOOK_INSTALL_SUMMARY` 始终记录；installer、锁与重复 attach 守卫未改。

回归：

- `ProviderDebugConfigTest.runtimeDiagnosticFailureDoesNotChangeResolutionOrEscape`：注入运行时异常/链接错误，开关开与关两种情况下 `applyDiagnostics` 与 `resolveDetailed` 结果一致、不外抛、`isDebugEnabled` 不变。
- `ProviderApplicationBootstrapTest.installerReturnedFailureKeepsInstallerSingleAndSummaryRecorded`：同两类异常下，两次 attach 后 installer 恰好执行 1 次、`HOOK_INSTALL_SUMMARY` 记录 1 次、出现失败记录且无 `HOOK_INSTALL_FAILED`。
- 未做变异验证（未临时去掉边界确认新测试会失败）。

本地验证：

- 定向：`provider-core` 64、`provider-hook-api102` 20、`provider-runtime-diagnostics` 23、`player-salt` 41 tests，均 0 failure / 0 skip；`:player-salt:assembleDebug` 成功。
- `writeSourceIdentityReport testV5Matrix assembleV5MatrixDebug` 成功；依赖改动代码的 17 个模块全部重跑，共 525 tests / 0 failure / 0 skip。解析器、`reflection-core`、封面、scope 探针等不依赖改动的模块为 up-to-date 未重跑；未运行 `testArtworkProvider` / `assembleArtworkProviderDebug`，因此没有可与前次 900 对比的新全量总数。
- 源码身份重算：705 个输入，dirty，sourceDigest=`21ab2ae070d2a86d6b2fb828b11079738d721c033de17ef5d062807033405258`（与 Gradle 报告一致）；`release/v5-provider-matrix.json` 仍为 `4D64559E…02720B`。
- 设备上已确认的候选仍是 `ECBE2EAC…`（digest `c8c288b5…`），它**不含**本节修改；本节没有新的设备证据。

范围外未改：`register` 在 `registerReceiver` 之前置 `registrationAttempted=true` 的问题仍在，只影响诊断页可用性。（同日已另行处理，见第 7 节。）

## 7. 诊断注册失败后可有限重试（2026-11-14）

状态：**代码已改，本地回归通过；未安装、未做设备验证**。只影响 Provider 诊断页能否回读，不影响启动、Hook 安装或供词。

- 问题：[ProviderRuntimeDiagnostics](<../provider-runtime-diagnostics/src/main/java/io/github/andrealtb/coloroslyrics/provider/runtimediagnostics/ProviderRuntimeDiagnostics.java>) 在调用 `registerReceiver` 之前就置 `registrationAttempted=true`，一次注册失败后本进程的诊断回读永久不可用。共享 bootstrap 每进程只调用一次，本就不会重试；但 Readify（`Application.attach` 与每次 `MediaSession` 构造）和 Universal（system_server 每次 `setMetadata` 等路径的 `ensureRequestReceiver`）会重复调用，这两处本可以在后续调用中恢复。
- 修改：布尔标记改为计数 `registrationAttempts`，上限 `MAX_REGISTRATION_ATTEMPTS = 3`。只有通过进程匹配、真正进入注册的调用才计数；注册成功后由既有 `receiver != null` 判断终止。上限防止持续失败的平台调用在 Universal 的 `setMetadata` 等热路径上反复抛出。异常边界、权限、导出标志、回包内容与调用时机均未改。
- 回归（`RuntimeDiagnosticsLifecycleTest`）：`failedRegistrationIsRetriedOnALaterCall`（首次失败、第二次成功、之后不再注册）、`persistentRegistrationFailureStopsAfterBoundedAttempts`（10 次调用只尝试 3 次）、`processMismatchDoesNotConsumeRegistrationAttempts`。
- 本地验证：`writeSourceIdentityReport testV5Matrix assembleV5MatrixDebug` 成功；依赖改动的 18 个模块全部重跑，551 tests / 0 failure / 0 skip。sourceDigest=`5d53d390ce8717d2f0fcb1e80f52e836c64db281b79c64a540c2e5259533f93a`（705 输入，dirty）。未做变异验证。
- 未覆盖：没有设备上“注册失败”的真实样本，重试路径只有本地证据。
