# M0 首个实施切片：原生 LSP scope 请求探针

编制日期：2026-11-12；设备证据更新：2026-11-13。状态：**本地切片已验证；用户在 OnePlus 13 / Android 17 / LSPosed 2.2.1 (7941) / API 102 上手动确认允许、拒绝、不再询问均正常。跨型号、真实 Provider 接入等仍未覆盖，M0 未全部通过。**

本探针仅解决下一大版本最早的框架可行性问题，不代表 Provider 商店、Compose 管理 UI、全专用模块动态授权或整个 M0 已完成。

## 1. 基线记录与不变项

- Bridge 源码基线：44aca8e4828a1b1257264bcc629c7cf5f7778061；4.5.4 / 149。
- Providers 源码基线：b9f82cc2208df4c767c6a371db89dae03555a994。
- 上述是已有用户反馈/送测所依附的源码基线，不是本轮新增设备验收。仅记录，没有创建 Git tag、提交或发布。
- 开始实施前，Bridge 已有两份 README 的计划入口修改及未跟踪的下一大版本计划；Providers 已有未跟踪 artifacts，均保留不动。
- 正式 Provider 矩阵、正式包名/版本、签名、scope 策略、ProcessPolicy、Hook 与 MediaSession 发布逻辑保持不变。
- 本轮生产契约 SHA-256：[Bridge 发布契约](<../../ColorOS-Live-Lyrics-Bridge/release/bridge-release-contract.json>) 为 079B5228CDA78B7A29F0B68F44BE6811EC680E20937098DF99F44AA224E1092D；[Provider 矩阵](<../release/v5-provider-matrix.json>) 为 4D64559EA09988EA49A7F90FC2498B3C9AF19601B5A644D3D1DCCCFC2A02720B。
- Universal、动态封面和 Readify 均未改动；它们不能因共享服务存在就被算作已完成新的管理适配。

## 2. 为什么先做隔离探针

服务调用依据 [libxposed service 102.0.0 固定源码](<https://raw.githubusercontent.com/libxposed/service/3318940876192e29cf6ab07637e899e22a87ebf0/service/src/main/java/io/github/libxposed/service/XposedService.java>)，本轮复核于 2026-11-12；不把 API 声明当作具体框架行为通过的证明。

直接给正式 Provider 加实验入口会带来测试签名覆盖失败、数据迁移和重复歌词 Hook 的干扰。因此新增可选的 [provider-scope-probe 模块](<../provider-scope-probe/build.gradle.kts>)：

- 包名为 io.github.andrealtb.coloroslyrics.scopeprobe，与所有正式 Provider/Bridge 分离，禁止备份测试提交标记。
- 仅显式设置 includeScopeProbe=true 时纳入 Gradle，默认构建、矩阵 CI 和发布资产不包含它；Release variant 禁用。
- 复用 [ProviderModuleApplication](<../provider-settings-api102/src/main/kotlin/io/github/andrealtb/coloroslyrics/provider/settings102/ProviderModuleApplication.kt>) 和 [ProviderServiceState](<../provider-settings-api102/src/main/kotlin/io/github/andrealtb/coloroslyrics/provider/settings102/ProviderServiceState.kt>)，没有第二次注册框架服务监听器。
- API 102 模块入口是无操作的 XposedModule 子类，不依赖 provider-hook-api102，不安装任何播放器/SystemUI Hook，不改 metadata、不取词。
- 宿主白名单取自 [机器矩阵](<../release/v5-provider-matrix.json>) 的 player-salt 条目，生成进 BuildConfig 和 APK 内 scope.list，不接收 Intent/网络给出的包名。当前样本仅验证一个宿主，不宣称全矩阵完成。
- 默认 staticScope 与当前矩阵一致；仅测试包允许显式 scopeProbeStaticScope=false 作对照，不修改生产矩阵或 convention。
- 页面是临时的原生 Android 调试仪表，不是下一版管理 UI 原型，更不替代已确定的 Kotlin + Compose Material 3 技术路线。

## 3. 已实现的行为

1. 打开、返回前台、服务连接和手动刷新只做 getScope；不会请求授权。
2. 只有前台用户点击请求按钮才调用本模块服务的 requestScope；重新回读，仅请求缺少的编译期允许宿主；不删除任何已有 scope。
3. 提交前在进程内原子认领并同步持久化一次性标记。写盘失败不发送；多个页面持有旧状态也不能重复认领；连点、旋转、服务重连、进程重建、失败或超时不会自动或直接再次提交。
4. 框架自己产生原生授权通知；本 App 没有通知权限、NotificationCompat 仿制通知或自绘允许/拒绝权限页。
5. 批准/失败回调均再次 getScope。批准列表不是保存成功证明，失败回调也不直接当成用户拒绝。
6. API 的失败字符串不提供可靠的“拒绝 / 不再询问”类型，本页只展示 FAILED_CALLBACK，不解析错误文案伪造分类。
7. 服务丢失、回读失败时 scope=unknown；服务世代和请求编号隔离迟到/重复回调。观察窗超时后先回读、不重试。
8. 60 秒是回调观察窗，不是 Binder 硬超时；SDK 没有取消挂起请求的 API。关闭页面会解绑 UI 并关闭本地 worker，不会声称已撤销框架通知。
9. Binder 操作在有明确 start/close 所有者的单个 worker 执行；结果回到主线程。配置变化保留无 Activity 引用的 session，终结时关闭。
10. 摘要仅显示编译身份、框架身份、允许宿主、目标子集和有界事件；不复制原始异常/框架失败文案，不读取播放器数据，不收集私人路径、歌词或凭据。

这是保守的单次试验工具，不是最终管理协议的持久化事务实现。进程重建后可只读 reconcile，但不会假定仍能收到旧回调。正式的跨 App 请求鉴权、requestId、安全恢复和全部专用 Provider 适配仍归 M4。

## 4. 本地构建（不安装、不连接设备）

从工作区根运行：

~~~powershell
cmd.exe /d /c 'scripts\dev.cmd provider "-PincludeScopeProbe=true" :provider-scope-probe:testDebugUnitTest :provider-scope-probe:assembleDebug'
~~~

上面的 cmd.exe 包装保留 -Pkey=value，避免 PowerShell 调用批处理时将 true 错当成 Gradle task。如需对照 dynamic 元数据，仅在同一命令引号内加 "-PscopeProbeStaticScope=false"。禁止把该参数当成生产模块迁移完成的证据。

生成 APK 是默认本地 Debug 签名的隔离测试包，不是正式 Release。当前本地验证记录在下节补齐。

## 5. 验证记录

- 已添加纯 JVM 行为测试：服务缺失、生命周期只读、已有 scope 保留、提交前回读/持久化、仅请求缺少白名单、连点、回调与回读矛盾、部分批准、失败/超时/进程重建不重放、Binder 死亡、迟到/重复回调、取消与摘要容量。
- 最终 28 个 JVM 测试：0 失败、0 错误、0 跳过。staticScope=true/false 两种配置均测试及 assembleDebug 成功；[最终测试结果](<../provider-scope-probe/build/test-results/testDebugUnitTest/TEST-io.github.andrealtb.coloroslyrics.scopeprobe.ScopeProbeControllerTest.xml>) 为 dynamic 配置的 28 项结果（不是 56 个不同测试）。
- [静态配置构建日志](<../provider-scope-probe/build/m0-static-validation-final2.log>) 与 [动态配置构建日志](<../provider-scope-probe/build/m0-dynamic-validation-final2.log>) 均为 BUILD SUCCESSFUL。初次 Windows 参数转发、AGP 9 generated source 接线、Kotlin 属性推断错误均已定位并修复；发布/正式模块代码没有为绕过错误而修改。
- [普通 projects 输出](<../provider-scope-probe/build/m0-default-projects-1.log>) 确认不带 opt-in 参数时没有探针；探针 tasks 清单没有 Release variant。以上 build/ 文件为本地生成证据，不入库，干净检出后需按本节命令重新生成。
- 最终两份 APK 均经 aapt2 核对独立包名、版本、minSdk=27/targetSdk=37、Debug 与入口；实际 APK 内三份 Xposed 资源核对通过：仅 com.salt.music、无操作 ScopeProbeModuleEntry，以及各自 staticScope 值。
- apksigner verify 均通过，签名为 Android Debug，证书 SHA-256 为 5A6FD43FC11B4C70FF12B6F82A7261C2ED9C4945692B3D818CA50BC64C8DD869。不是正式发行证书，不能当作正式 RC。
- 两份 APK 各 2,858,996 字节：[static 对照包](<../../artifacts/m0-scope-probe-20261112/LSP-Scope-PoC-0.1-m0-static-debug.apk>) 的 SHA-256 为 EE55EA669F0A7DFBC26D852B0C1E5BAE16918399D212BD9EAF6DACFF757BA161；[dynamic 对照包](<../../artifacts/m0-scope-probe-20261112/LSP-Scope-PoC-0.1-m0-dynamic-debug.apk>) 为 5A6A6E1A520759075A6324A03BD2C57E245AFDDA1272E7EF722CC1F4F6DC52C6。构建仅有全局 path-check 实验提示及探针为旧 SDK 保留的系统栏颜色 API 弃用警告。
- 初始本地阶段没有设备操作。2026-11-13 用户回传 staticScope=true 的原生通知截图与 APPROVED_CALLBACK/目标 scope 回读摘要，单次允许闭环通过；来源与局限见第 6.1 节。本次记录阶段未通过 ADB 读取、安装、点击或清理设备。

## 6. 用户执行的设备验收清单（尚未授权代操作）

使用隔离测试包，不能用默认 Debug 签名去覆盖正式 Provider。static/dynamic 两包同包名且同签名，是先后覆盖安装的元数据对照，不能并存；仅重新安装不会清除本地一次性标记，也不清除框架的“不再询问”记录。样本宿主是 Salt，开始前确认测试机确有该宿主；没有时应另选矩阵中的专用样本重新构建，不能擅自要求安装播放器。准备前记录 ColorOS 构建、框架完整版本/API、探针版本与 staticScope、原始 scope、框架通知权限/渠道状态；不要公开私人日志。

| 场景 | 操作与预期证据 | 当前状态 |
| --- | --- | --- |
| 服务前置条件 | 在目标框架启用该探针后，从其桌面/模块设置入口打开；记录服务是否交付。未连接只能标未知，不推断一定未启用 | 部分覆盖：本轮先未交付，后 SERVICE_BOUND；不证明服务死亡/重建测试通过 |
| 未授权宿主 | 在框架中确认样本宿主尚未加入该探针 scope；打开/旋转/返回/刷新均不应弹通知 | 未覆盖 |
| 允许 | 点击一次；由框架发出的通知中模块身份必须是 LSP Scope PoC，而不是 Bridge 或正式 Salt Provider；允许后回读目标存在 | 用户设备证据通过：单次 staticScope=true，LSPosed 2.2.1 / API 102；见 6.1 |
| 拒绝 | 独立一轮点击后在框架拒绝；保留实际回调与回读，不自动重试 | 用户设备确认，见 6.2 |
| 不再询问 | 独立一轮选择不再询问；后续只读恢复，不把 FAILED_CALLBACK 强行分类；框架是否拦截后续显式新实验须实际观察 | 用户设备确认，见 6.2 |
| 回调缺失/服务死亡 | 观察窗结束或服务重连后仍不重复请求；返回页面可回读，未知不能标已激活 | 未覆盖 |
| 已有 scope | 已含样本宿主时按钮不可请求，其他原有 scope 不被删；无通知不等于失败 | 未覆盖 |
| 静/动态对照 | 分别保存 staticScope=true/false 测试包、APK 摘要及回读；不得先验断言 true 总拒绝或 false 总通过 | 用户确认：true 允许、false 拒绝均正常；其余选择未逐项配对 |
| 真实 Provider 接入 | 隔离探针通过后，才给实际专用 Provider 做同身份接入和回归；本样本不能证明真实 Provider 已完成 | 未覆盖 |

每份探针本地数据只提交一次。需要新一轮时，**先由用户在框架确认没有挂起请求**，再从 Android 设置手工清除“仅此 PoC 测试包”的数据。不得清理 Bridge/正式 Provider/播放器数据，不重置框架数据库，也不为测试不再询问偷偷清除框架记录。打开同一已提交数据会显示 RESTORED_UNKNOWN，只能回读。

当前只确认单设备/单探针/单次允许，不能标整项框架验收或 M0 全部通过。托管/目录签名、Compose 稳定依赖与 UI 原型仍是 M0 其他未完成门禁；下一段本地工作可推进其准备或 M1 兼容诊断，但不得宣布商店可发布。

### 6.1 用户设备回传：staticScope=true 原生通知与允许闭环（2026-11-13）

**证据来源：用户提供截图及探针复制摘要，不是 Agent 通过 ADB 采集。** 原始材料仅留在工作区仓库之外的本地 artifacts，不入库、不自动公开；此处记录不包含私人播放器数据的结论。

- [原生通知截图](<../../artifacts/m0-scope-probe-20261112/static-allow-notification-20261113.png>)：402×330 PNG，SHA-256 为 FABD8AA9B4D6A047F2C339CB096AFCC060A737625929D7C8A1E1D6646213CECA。可见框架“作用域请求”及“允许 / 拒绝 / 不再询问”，请求模块为 io.github.andrealtb.coloroslyrics.scopeprobe，目标为 com.salt.music。
- [用户回传摘要](<../../artifacts/m0-scope-probe-20261112/static-allow-user-summary-20261113.txt>) 报告：0.1-m0-static / 1，SDK 37，staticScope=true，LSPosed / 2.2.1 / API 102，service=true，APPROVED_CALLBACK，批准目标及回读目标均为 com.salt.music，额外 scope=0，本地提交标记=true。
- 可见事件链：SERVICE_UNAVAILABLE → SERVICE_BOUND → SCOPE_READ_OK → REQUEST_SUBMITTING → CALLBACK_APPROVED_CALLBACK → SCOPE_READ_OK。最初的 SERVICE_UNAVAILABLE 随后恢复，不是本轮授权失败证据。
- 判定：**该用户设备、所报告框架与此探针上，声明内宿主的原生请求、允许回调及 scope 回读闭环通过。staticScope=true 并不在这个场景下阻止 requestScope。** 不需要仅为触发通知而先验要求 false，更不能据此全局翻转生产元数据。
- 已由用户设备确认（2026-11-13）：允许、拒绝、不再询问三类原生交互均正常；探针未从第二轮事件链独立取证，见 6.2。仍未覆盖：跨型号/框架、超时与服务死亡、真实 Provider 身份接入、宿主加载与歌词、正式签名/R8。
- 手机型号、完整 ColorOS 构建、框架分支/版本代码尚未提供；不从 SDK 37 推定具体 ColorOS 版本。摘要报告的安装身份尚未由设备 APK/证书独立核验；本地 APK 摘要不能直接当成已安装 APK 的测量值。
- 用户说明手机已连接；这本身不作为允许安装、清数据、调整作用域或点击授权的许可。下一轮设备操作需另行明确范围，且只针对探针，不动正式模块或播放器，不重启 SystemUI。


### 6.2 探针一次性限制与用户手动结论（2026-11-13）

- 用户指出：探针每份数据只允许提交一次，第二次必须清除数据，该设计不适合连续人工对照。**纠正此前推断：事件计数会在进程/控制器重建后重新开始；两份摘要相同不能证明用户没有清数据或没有发起第二轮请求。** 不再用此推断否定用户手动测试结论。
- 用户随后手动验证并反馈：**允许、拒绝、不再询问均正常工作**。按用户设备确认记录，不再要求补充探针证据。
- 设备与框架由本轮只读核验支持：OnePlus 13（PJZ110），Android 17 / SDK 37，构建 PJZ110_17.0.0.103(CN01)，LSPosed 框架 2.2.1 (7941) / API 102；管理器 APK 自身为 2.1.0 / 7769，两者不是同一版本号。
- 仍**未覆盖**：跨型号与其它框架、超时/服务死亡、真实专用 Provider 身份接入、宿主加载与歌词、正式签名/R8。单机单探针的确认不等于全矩阵验收。
- **对正式设计的约束**：一次性提交只是探针手段，不能进入产品。真实作用域编排必须允许用户重复发起、在拒绝后有节流与可解释状态，并把“不再询问”作为框架侧状态呈现，而不是靠清数据重试。
