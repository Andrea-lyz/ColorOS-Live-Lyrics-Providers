// node --test scripts/catalog/
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { signPayload, verifyEnvelope, validatePayload, DOMAIN } from './catalog-envelope.mjs';

const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'test-fixtures');
const read = (name) => fs.readFileSync(path.join(dir, name), 'utf8');
const privatePem = read('TEST-ONLY-catalog-signing-key.pem');
const publicPem = read('TEST-ONLY-catalog-signing-key.pub.pem');
const KEY_ID = 'test-only-2026';
const payload = () => JSON.parse(read('fixture-payload.json'));
const keys = { [KEY_ID]: publicPem };

test('committed fixture envelope verifies with the test-only key', () => {
  const verified = verifyEnvelope(JSON.parse(read('fixture-envelope.json')), keys);
  assert.deepEqual(verified, payload());
});

test('sign then verify round-trips the payload', () => {
  assert.deepEqual(verifyEnvelope(signPayload(payload(), privatePem, KEY_ID), keys), payload());
});

test('a flipped payload byte is rejected', () => {
  const envelope = signPayload(payload(), privatePem, KEY_ID);
  const bytes = Buffer.from(envelope.payload, 'base64');
  bytes[bytes.length - 2] ^= 1;
  envelope.payload = bytes.toString('base64');
  assert.throws(() => verifyEnvelope(envelope, keys), /bad signature/);
});

test('a signature from another key or an unknown keyId is rejected', () => {
  const other = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  assert.throws(() => verifyEnvelope(signPayload(payload(), other, KEY_ID), keys), /bad signature/);
  assert.throws(() => verifyEnvelope(signPayload(payload(), privatePem, 'other-key'), keys), /unknown keyId/);
});

test('signature without the domain prefix is rejected', () => {
  const envelope = signPayload(payload(), privatePem, KEY_ID);
  const bare = crypto.sign('sha256', Buffer.from(envelope.payload, 'base64'), { key: privatePem, dsaEncoding: 'der' });
  envelope.signature = bare.toString('base64');
  assert.throws(() => verifyEnvelope(envelope, keys), /bad signature/);
  assert.equal(DOMAIN.toString('ascii'), 'CLL-CATALOG-V1\n');
});

test('non-P-256 signing keys are refused', () => {
  const rsa = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  assert.throws(() => signPayload(payload(), rsa, KEY_ID), /P-256/);
});

test('the signer refuses invalid payloads', () => {
  const cases = [
    (p) => { p.schemaVersion = 2; },
    (p) => { p.catalogRevision = 0; },
    (p) => { p.expiresAtEpochSeconds = p.issuedAtEpochSeconds + 191 * 86400; },
    (p) => { p.channel = 'nightly'; },
    (p) => { p.entries[0].artifact.sha256 = '0'.repeat(64); },
    (p) => { p.entries[0].artifact.bytes = 0; },
    (p) => { p.entries[0].versionCode = 0; },
    (p) => { p.entries[0].sourceCommit = '0'.repeat(40); },
    (p) => { p.entries[0].artifact.urls = ['http://github.com/a.apk']; },
    (p) => { p.entries[0].artifact.urls = ['https://evil.example/a.apk']; },
    (p) => { p.entries[0].artifact.urls = ['https://user@github.com/a.apk']; },
    (p) => { p.entries[1].applicationId = p.entries[0].applicationId; },
    (p) => { p.entries[0].kind = 'unknown'; },
    (p) => { p.entries[0].artifact.signerSha256 = []; },
    (p) => { p.entries[0].releaseNotes = 'bell\u0007'; },
  ];
  for (const mutate of cases) {
    const p = payload();
    mutate(p);
    assert.throws(() => validatePayload(p), /catalog:/, mutate.toString());
    assert.throws(() => signPayload(p, privatePem, KEY_ID), /catalog:/);
  }
});
