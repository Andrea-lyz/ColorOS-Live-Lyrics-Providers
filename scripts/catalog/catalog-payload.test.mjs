// node --test scripts/catalog/catalog-payload.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildPayload, renewPayload, matrixModules, releaseTag } from './catalog-payload.mjs';
import { parseBadging, parseSigners } from './apk-facts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const matrix = JSON.parse(fs.readFileSync(path.join(root, 'release', 'v5-provider-matrix.json'), 'utf8'));
const SIGNER = 'ff38544ba21922c35989097fe6f2ad0bda434b4d0bb611e016d51d7862d36195';
const COMMIT = '215b0c9e8add8e2886f4913171e989cc6f9e7f73';
const NOW = 1_794_614_400;
const options = { repo: 'Andrea-lyz/ColorOS-Live-Lyrics-Providers', sourceCommit: COMMIT, expectedSigner: SIGNER, nowEpochSeconds: NOW };

const modules = matrixModules(matrix);
const salt = modules.get('player-salt');

function facts(module, overrides = {}) {
  const m = modules.get(module);
  return {
    applicationId: m.applicationId,
    versionCode: m.versionCode,
    versionName: m.versionName,
    minSdk: 27,
    bytes: 1000,
    sha256: 'a'.repeat(64),
    signerSha256: [SIGNER],
    ...overrides,
  };
}

const update = (module, overrides = {}, assetName = `${module}.apk`) => ({ module, assetName, facts: facts(module, overrides) });

test('matrix gives every Provider and the artwork app a kind', () => {
  assert.equal(modules.get('universal-provider').kind, 'universal-lyrics');
  assert.equal(modules.get('artwork-provider-am').kind, 'artwork');
  assert.equal(salt.kind, 'dedicated-lyrics');
  assert.equal(modules.size, matrix.providers.length + matrix.artworkProviders.length);
});

test('first catalog takes identity from the APK and version from the matrix', () => {
  const payload = buildPayload({ matrix, previous: null, updates: [update('player-salt')], options });
  assert.equal(payload.catalogRevision, 1);
  assert.equal(payload.expiresAtEpochSeconds - payload.issuedAtEpochSeconds, 180 * 86400);
  const [entry] = payload.entries;
  assert.equal(entry.applicationId, salt.applicationId);
  assert.equal(entry.versionCode, salt.versionCode);
  assert.equal(entry.sourceCommit, COMMIT);
  assert.equal(entry.artifact.urls[0],
    `https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Providers/releases/download/${releaseTag('player-salt', salt.versionName)}/player-salt.apk`);
});

test('APK and matrix must agree', () => {
  const cases = [
    { applicationId: 'io.example.other' },
    { versionCode: salt.versionCode + 1 },
    { versionName: '9.9.9' },
    { signerSha256: ['b'.repeat(64)] },
    { signerSha256: [SIGNER, 'b'.repeat(64)] },
  ];
  for (const overrides of cases) {
    assert.throws(() => buildPayload({ matrix, previous: null, updates: [update('player-salt', overrides)], options }), /catalog-payload/);
  }
  const unknown = { module: 'player-unknown', assetName: 'u.apk', facts: facts('player-salt') };
  assert.throws(() => buildPayload({ matrix, previous: null, updates: [unknown], options }), /not in the matrix/);
  assert.throws(() => buildPayload({ matrix, previous: null, updates: [update('player-salt', {}, '../evil.apk')], options }), /unsafe asset/);
  assert.throws(() => buildPayload({ matrix, previous: null, updates: [update('player-salt'), update('player-salt')], options }), /twice/);
});

test('next revision keeps other entries and only bumps changed ones', () => {
  const first = buildPayload({ matrix, previous: null, updates: [update('player-salt'), update('universal-provider')], options });
  const bumped = structuredClone(matrix);
  bumped.providers.find((p) => p.module === 'player-salt').versionCode += 1;
  bumped.providers.find((p) => p.module === 'player-salt').versionName = '1.1.3';
  const saltNext = { module: 'player-salt', assetName: 's.apk', facts: { ...facts('player-salt'), versionCode: salt.versionCode + 1, versionName: '1.1.3', sha256: 'c'.repeat(64) } };
  const second = buildPayload({ matrix: bumped, previous: first, updates: [saltNext], options: { ...options, nowEpochSeconds: NOW + 100 } });
  assert.equal(second.catalogRevision, 2);
  assert.deepEqual(second.entries.find((e) => e.providerId === 'universal-provider'), first.entries.find((e) => e.providerId === 'universal-provider'));
  assert.equal(second.entries.find((e) => e.providerId === 'player-salt').versionName, '1.1.3');
});

test('a published versionCode can never be replaced or lowered', () => {
  const first = buildPayload({ matrix, previous: null, updates: [update('player-salt')], options });
  assert.throws(() => buildPayload({ matrix, previous: first, updates: [update('player-salt', { sha256: 'd'.repeat(64) })], options }), /already published/);
  assert.throws(() => buildPayload({ matrix, previous: first, updates: [update('player-salt')], options }), /nothing changed/);
  const lowered = structuredClone(matrix);
  lowered.providers.find((p) => p.module === 'player-salt').versionCode -= 1;
  const low = { module: 'player-salt', assetName: 's.apk', facts: { ...facts('player-salt'), versionCode: salt.versionCode - 1 } };
  assert.throws(() => buildPayload({ matrix: lowered, previous: first, updates: [low], options }), /lower than published/);
});

test('renewal keeps entries and advances revision and validity', () => {
  const first = buildPayload({ matrix, previous: null, updates: [update('player-salt')], options });
  const renewed = renewPayload(first, NOW + 170 * 86400);
  assert.equal(renewed.catalogRevision, 2);
  assert.deepEqual(renewed.entries, first.entries);
  assert.equal(renewed.expiresAtEpochSeconds, NOW + 350 * 86400);
});

test('aapt2 badging and apksigner output are parsed strictly', () => {
  const badging = "package: name='io.example.salt' versionCode='8' versionName='1.1.2' platformBuildVersionName='15'\nsdkVersion:'27'\ntargetSdkVersion:'35'\n";
  assert.deepEqual(parseBadging(badging), { applicationId: 'io.example.salt', versionCode: 8, versionName: '1.1.2', minSdk: 27 });
  assert.throws(() => parseBadging('nothing'), /package line/);
  const signer = `Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: ${SIGNER.toUpperCase()}\n`;
  assert.deepEqual(parseSigners(signer), [SIGNER]);
  assert.throws(() => parseSigners(`DOES NOT VERIFY\nSigner #1 certificate SHA-256 digest: ${SIGNER}\n`), /Verifies/);
  assert.throws(() => parseSigners('Verifies\n'), /no signer/);
});
