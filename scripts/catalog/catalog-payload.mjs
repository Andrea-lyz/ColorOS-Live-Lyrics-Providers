// Builds an unsigned store catalog payload from the machine matrix and the APKs actually built
// (plan §14.6 follow-up ①). Identity, size, digest and signer always come from the APK; version
// and kind always come from the matrix; the two must agree. Nothing is typed in by hand.
import { validatePayload, SCHEMA_VERSION } from './catalog-envelope.mjs';

export const DEFAULT_TRUSTED_HOSTS = ['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'];
export const DEFAULT_VALIDITY_DAYS = 180;
export const STORE_PROTOCOL = 1;
const ASSET_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$/;
const REPO = /^[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/;

function fail(message) {
  throw new Error(`catalog-payload: ${message}`);
}

/** module -> {applicationId, versionName, versionCode, kind} from v5-provider-matrix.json. */
export function matrixModules(matrix) {
  const modules = new Map();
  for (const p of matrix.providers ?? []) {
    const kind = p.module === 'universal-provider' ? 'universal-lyrics' : 'dedicated-lyrics';
    modules.set(p.module, { applicationId: p.applicationId, versionName: p.versionName, versionCode: p.versionCode, kind });
  }
  for (const a of matrix.artworkProviders ?? []) {
    modules.set(a.module, { applicationId: a.applicationId, versionName: a.versionName, versionCode: a.versionCode, kind: 'artwork' });
  }
  if (modules.size === 0) fail('matrix has no modules');
  return modules;
}

/** Release tag for one Provider version (plan §14.5.2). */
export const releaseTag = (module, versionName) => `${module}-v${versionName}`;

/**
 * @param matrix parsed v5-provider-matrix.json
 * @param previous previous verified payload object, or null for the first catalog
 * @param updates [{module, assetName, facts: {applicationId, versionCode, versionName, minSdk, bytes, sha256, signerSha256[]}, releaseNotes?}]
 * @param options {repo, sourceCommit, expectedSigner, nowEpochSeconds, validityDays?, channel?}
 */
export function buildPayload({ matrix, previous, updates, options }) {
  const { repo, sourceCommit, expectedSigner, nowEpochSeconds } = options;
  const validityDays = options.validityDays ?? DEFAULT_VALIDITY_DAYS;
  const channel = options.channel ?? 'stable';
  if (!REPO.test(repo ?? '')) fail('repo must be owner/name');
  if (!/^[0-9a-f]{40}$/.test(sourceCommit ?? '')) fail('sourceCommit must be a full lowercase commit hash');
  if (!/^[0-9a-f]{64}$/.test(expectedSigner ?? '')) fail('expectedSigner must be a lowercase SHA-256');
  if (!Number.isSafeInteger(nowEpochSeconds) || nowEpochSeconds <= 0) fail('nowEpochSeconds');
  if (!Array.isArray(updates) || updates.length === 0) fail('no updates');

  const modules = matrixModules(matrix);
  const byModule = new Map();
  if (previous) {
    if (previous.schemaVersion !== SCHEMA_VERSION) fail('previous catalog has another schema');
    if (previous.channel !== channel) fail('previous catalog is another channel');
    for (const entry of previous.entries) {
      if (!modules.has(entry.providerId)) fail(`previous entry ${entry.providerId} is no longer in the matrix`);
      byModule.set(entry.providerId, entry);
    }
  }

  const seen = new Set();
  for (const update of updates) {
    const { module, assetName, facts } = update;
    const expected = modules.get(module);
    if (!expected) fail(`${module} is not in the matrix`);
    if (seen.has(module)) fail(`${module} updated twice`);
    seen.add(module);
    if (!ASSET_NAME.test(assetName ?? '')) fail(`${module}: unsafe asset name ${assetName}`);
    if (facts.applicationId !== expected.applicationId) fail(`${module}: APK package ${facts.applicationId} != matrix ${expected.applicationId}`);
    if (facts.versionCode !== expected.versionCode || facts.versionName !== expected.versionName) {
      fail(`${module}: APK version ${facts.versionName}/${facts.versionCode} != matrix ${expected.versionName}/${expected.versionCode}`);
    }
    if (facts.signerSha256.length !== 1 || facts.signerSha256[0] !== expectedSigner) {
      fail(`${module}: APK is not signed by exactly the release certificate`);
    }
    const old = byModule.get(module);
    if (old) {
      if (facts.versionCode < old.versionCode) fail(`${module}: versionCode ${facts.versionCode} is lower than published ${old.versionCode}`);
      if (facts.versionCode === old.versionCode) {
        if (facts.sha256 !== old.artifact.sha256) fail(`${module}: versionCode ${facts.versionCode} is already published with different bytes`);
        continue; // identical re-publish: keep the published entry untouched
      }
    }
    const tag = releaseTag(module, facts.versionName);
    const entry = {
      providerId: module,
      kind: expected.kind,
      applicationId: facts.applicationId,
      versionName: facts.versionName,
      versionCode: facts.versionCode,
      minSdk: facts.minSdk,
      minStoreProtocol: STORE_PROTOCOL,
      sourceCommit,
      artifact: {
        urls: [`https://github.com/${repo}/releases/download/${encodeURIComponent(tag)}/${assetName}`],
        bytes: facts.bytes,
        sha256: facts.sha256,
        signerSha256: [...facts.signerSha256],
      },
    };
    if (update.releaseNotes !== undefined) entry.releaseNotes = update.releaseNotes;
    byModule.set(module, entry);
  }

  // Matrix order keeps diffs between revisions small and readable.
  const entries = [...modules.keys()].filter((m) => byModule.has(m)).map((m) => byModule.get(m));
  const unchanged = previous && JSON.stringify(entries) === JSON.stringify(previous.entries);
  if (unchanged) fail('nothing changed; refusing to issue a new revision');
  const payload = {
    schemaVersion: SCHEMA_VERSION,
    catalogRevision: previous ? previous.catalogRevision + 1 : 1,
    issuedAtEpochSeconds: nowEpochSeconds,
    expiresAtEpochSeconds: nowEpochSeconds + validityDays * 86400,
    channel,
    trustedHosts: [...new Set([...DEFAULT_TRUSTED_HOSTS, ...(previous?.trustedHosts ?? [])])],
    entries,
  };
  validatePayload(payload);
  return payload;
}

/** Re-sign the same entries with a new validity window (no content change, new revision). */
export function renewPayload(previous, nowEpochSeconds, validityDays = DEFAULT_VALIDITY_DAYS) {
  validatePayload(previous);
  const payload = {
    ...previous,
    catalogRevision: previous.catalogRevision + 1,
    issuedAtEpochSeconds: nowEpochSeconds,
    expiresAtEpochSeconds: nowEpochSeconds + validityDays * 86400,
  };
  validatePayload(payload);
  return payload;
}
