// node --test scripts/catalog/resolve-release-tag.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveReleaseTag } from './resolve-release-tag.mjs';
import { toPublicKey } from './catalog-envelope.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const matrix = JSON.parse(fs.readFileSync(path.join(root, 'release', 'v5-provider-matrix.json'), 'utf8'));
const trust = JSON.parse(fs.readFileSync(path.join(root, 'release', 'catalog-trust.json'), 'utf8'));
const all = [...matrix.providers, ...matrix.artworkProviders];

test('every matrix module resolves from its own tag', () => {
  for (const m of all) {
    const r = resolveReleaseTag(`${m.module}-v${m.versionName}`, matrix);
    assert.equal(r.module, m.module);
    assert.equal(r.versionCode, m.versionCode);
    assert.match(r.assetName, /^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$/);
    assert.equal(r.gradleAssemble, `:${m.module}:assembleRelease`);
  }
  const salt = matrix.providers.find((p) => p.module === 'player-salt');
  assert.equal(resolveReleaseTag(`player-salt-v${salt.versionName}`, matrix).assetName,
    `ColorOS-Live-Lyrics-Provider-Salt-v${salt.versionName}.apk`);
  const artwork = matrix.artworkProviders[0];
  assert.equal(resolveReleaseTag(`${artwork.module}-v${artwork.versionName}`, matrix).assetName,
    `ColorOS-Live-Lyrics-Dynamic-Artwork-Provider-v${artwork.versionName}.apk`);
});

test('tags that do not match the matrix are refused', () => {
  assert.throws(() => resolveReleaseTag('player-salt-v9.9.9', matrix), /bump the matrix/);
  assert.throws(() => resolveReleaseTag('providers-v1.2.0', matrix), /no module/);
  assert.throws(() => resolveReleaseTag('v4.5.4', matrix), /not a Provider release tag/);
  assert.throws(() => resolveReleaseTag('player-salt-v1.1.2;rm', matrix), /not a Provider release tag/);
});

test('catalog trust file pins the release certificate and only P-256 keys', () => {
  assert.match(trust.releaseCertificateSha256, /^[0-9a-f]{64}$/);
  assert.equal(trust.channel, 'stable');
  for (const [keyId, encoded] of Object.entries(trust.keys)) {
    const key = toPublicKey(encoded);
    assert.equal(key.asymmetricKeyDetails.namedCurve, 'prime256v1', keyId);
    assert.ok(!keyId.startsWith('test-'), 'test keys must never be pinned for production');
  }
});
