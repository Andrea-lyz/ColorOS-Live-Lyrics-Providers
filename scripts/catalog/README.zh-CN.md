# Provider 商店目录签名工具

5.0 计划 M2（Bridge `docs/NEXT-MAJOR-RELEASE-PLAN.zh-CN.md` §6.2、§14.5）。客户端验签实现在 Bridge `management/catalog/CatalogVerifier.kt`，两边规则必须同步修改。

## 格式

- 信封：`envelopeVersion`=1、`keyId`、`algorithm`=`ECDSA_P256_SHA256`、`payload`（目录 JSON 的 UTF-8 字节，base64）、`signature`（DER，base64）。
- 签名内容是 `CLL-CATALOG-V1\n` 加上 payload 原始字节。客户端先验签，再只解析这些字节，所以不依赖 JSON 规范化。
- payload schema 1 的字段与限制见 `catalog-envelope.mjs` 中的 `validatePayload`。主要限制：有效期不超过 190 天，URL 只允许 https 且主机在 `trustedHosts` 中，摘要/大小/版本号/提交号不得为 0 或占位，同一包名或 providerId 不得重复。

## 生成目录内容

`build-catalog-payload.mjs` 由矩阵和实际构建出的 APK 生成下一版未签名目录，不手填任何摘要或证书：

- 包名、versionCode、versionName、minSdk 用 `aapt2 dump badging` 读取；签名者用 `apksigner verify --print-certs` 读取；大小和 SHA-256 直接对文件计算。
- 包名与版本必须和 `release/v5-provider-matrix.json` 一致，kind 也取自矩阵；APK 必须只由 `--expected-signer` 这一张证书签名（debug 包会被拒绝）。
- `--previous` 传入当前已发布的**已签名**目录，先验签再在其上生成，revision 加 1，未更新的条目原样保留。已发布的 versionCode 不能降低，也不能用不同字节重发；内容完全没变时拒绝生成新 revision。
- 下载地址为 `https://github.com/<repo>/releases/download/<providerId>-v<versionName>/<APK 文件名>`。
- `catalog-payload.mjs` 的 `renewPayload` 用于到期前只续期、不改内容。

```bash
node scripts/catalog/build-catalog-payload.mjs --repo Andrea-lyz/ColorOS-Live-Lyrics-Providers   --source-commit <完整提交号> --expected-signer <正式证书 SHA-256>   --apk player-salt=<Salt.apk> [--apk ...] [--notes player-salt=<说明.txt>]   [--previous catalog.json --previous-key-id <id> --previous-public-key <公钥.pem>]   [--build-tools <build-tools 目录>] --out catalog-payload.json
```

## 用法

```bash
node scripts/catalog/sign-catalog.mjs --payload catalog-payload.json --key-id <keyId> --out catalog.json --key <私钥.pem>
node scripts/catalog/verify-catalog.mjs --envelope catalog.json --trust release/catalog-trust.json
node --test scripts/catalog/catalog-envelope.test.mjs scripts/catalog/catalog-payload.test.mjs scripts/catalog/resolve-release-tag.test.mjs
node scripts/catalog/generate-catalog-key.mjs --key-id <keyId> --out <仓库外的路径>.pem
```

- 正式签名只在受保护的 GitHub Environment 中运行（D2），私钥通过 secret `CLL_CATALOG_SIGNING_KEY` 提供，不加 `--key`。工具不打印也不另存私钥；输出文件已存在时拒绝覆盖。
- 正式密钥 `cll-catalog-2026a`（主用）和 `cll-catalog-2026b`（备用）的公钥已填入 `release/catalog-trust.json` 和 Bridge `CatalogTrust.PRODUCTION_CATALOG_KEYS`。

## 发布流程（`.github/workflows/provider-store-release.yml`）

在 `main` 上的提交打 tag `<module>-v<versionName>`（版本须先在矩阵中提升；旧的 `providers-v*` 不触发）：

1. **build**：按矩阵解析 tag（`resolve-release-tag.mjs`），要求 tag 指向的提交在 `main` 上；跑目录工具测试和整份矩阵测试（共享代码可能影响所有模块）；动态封面另跑自身测试和协议镜像校验；正式签名构建该模块，只收集到一个 APK，按目录规则核对包名、版本和证书。
2. **publish**：创建该 tag 的 Release，只附这一个 APK 和它的 `.sha256`；Release 已存在则失败，不覆盖。随后从公开下载地址重新下载，核对 SHA-256。
3. **promote**：在受保护的 Environment `provider-catalog` 中运行，需要审批。再次下载已发布的 APK 作为目录数据来源；用 `release/catalog-trust.json` 验签 `store-catalog` 分支上的当前目录，在其上生成、签名、验签下一版；以普通 push（不强推）提交到 `store-catalog` 分支的 `catalog/stable.json`。所有晋级串行执行。push 后清除 jsDelivr 上该文件的缓存，并确认 Bridge 实际请求的 `cdn.jsdelivr.net` 返回新 revision（`fastly.jsdelivr.net` 的缓存独立且清不掉，不检查）：Bridge 优先读 jsDelivr，分支链接不清缓存最长会滞留 12 小时（raw GitHub 约 5 分钟自行过期）。CDN 仍未更新只记警告，不影响已完成的晋级；可手动执行 `curl https://purge.jsdelivr.net/gh/Andrea-lyz/ColorOS-Live-Lyrics-Providers@store-catalog/catalog/stable.json` 补清。

任一步失败，已发布的目录都不变。可选的发布说明放在 `release/notes/<module>/<versionName>.md`，同时用于 Release 和目录。

**启用前需要维护者在 GitHub 上完成**（2026-11-14 只读核对的状态）：

- [x] 生成两把密钥，公钥已填入两端；私钥在仓库外。
- [x] Environment `provider-catalog` 已建，其中有 secret `CLL_CATALOG_SIGNING_KEY` 和变量 `CLL_CATALOG_KEY_ID`（`cll-catalog-2026a`）。
- [ ] 给 `provider-catalog` 设置**必需审核人**。目前只有部署分支规则，没有审批，晋级会不经确认直接执行。
- [ ] 在 `provider-catalog` 的部署规则中允许 **tag** `*-v*`。目前启用了自定义规则但一条也没有，所以由 tag 触发的晋级会被拒绝。
- [ ] 添加仓库级 secret：`SIGNING_KEY`（release.jks 的 base64）、`KEY_STORE_PASSWORD`、`ALIAS`、`KEY_PASSWORD`。目前一个也没有，构建任务会在解码 keystore 时失败。证书指纹必须等于 `release/catalog-trust.json` 的 `releaseCertificateSha256`。
- [x] `main` 没有分支保护；`store-catalog` 分支尚不存在，首次晋级会新建它（revision 1）。

## 测试夹具

`test-fixtures/TEST-ONLY-catalog-signing-key.pem` 是**仅供测试的一次性密钥**，不得用于任何正式目录。`fixture-envelope.json` 由本工具用该密钥签出，并复制到 Bridge `app/src/test/resources/catalog/`（只复制公钥），用来证明 Node 签名与 Kotlin 验签字节一致。重新生成夹具后两处都要更新，并同步 Bridge 测试中的 `FIXTURE_PAYLOAD_SHA256`。
