// Provider store catalog envelope (plan §6.2/§14.5, D2): ECDSA P-256 / SHA-256 over
// DOMAIN || payloadBytes. The client verifies the exact signed bytes and only then parses
// them, so no JSON canonicalisation is involved. Keep the rules below in step with
// Bridge management/catalog/CatalogVerifier.kt; the shared fixture test proves the bytes match.
import crypto from 'node:crypto';

export const ENVELOPE_VERSION = 1;
export const ALGORITHM = 'ECDSA_P256_SHA256';
export const DOMAIN = Buffer.from('CLL-CATALOG-V1\n', 'ascii');
export const SCHEMA_VERSION = 1;
export const MAX_VALIDITY_SECONDS = 190 * 24 * 3600;
export const MAX_ENVELOPE_BYTES = 256 * 1024;
export const MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
export const KINDS = ['dedicated-lyrics', 'universal-lyrics', 'artwork'];
export const CHANNELS = ['stable', 'test'];

const HOST = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const APPLICATION_ID = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const KEY_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const HEX40 = /^[0-9a-f]{40}$/;
const CONTROL = /[\u0000-\u0009\u000b-\u001f\u007f]/;

function fail(message) {
  throw new Error(`catalog: ${message}`);
}

const isInt = (v) => Number.isSafeInteger(v);
const allZero = (s) => /^0+$/.test(s);

/** Structural rules the signer enforces before signing (the client re-checks everything). */
export function validatePayload(payload) {
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) fail('payload must be an object');
  if (payload.schemaVersion !== SCHEMA_VERSION) fail(`schemaVersion must be ${SCHEMA_VERSION}`);
  const { catalogRevision: rev, issuedAtEpochSeconds: issued, expiresAtEpochSeconds: expires } = payload;
  if (!isInt(rev) || rev < 1) fail('catalogRevision must be a positive integer');
  if (!isInt(issued) || issued <= 0 || !isInt(expires) || expires <= issued) fail('invalid validity window');
  if (expires - issued > MAX_VALIDITY_SECONDS) fail('validity window longer than 190 days');
  if (!CHANNELS.includes(payload.channel)) fail('unknown channel');
  const hosts = payload.trustedHosts;
  if (!Array.isArray(hosts) || hosts.length < 1 || hosts.length > 16 || !hosts.every((h) => typeof h === 'string' && HOST.test(h))) {
    fail('trustedHosts must be 1..16 lowercase host names');
  }
  if (!Array.isArray(payload.entries) || payload.entries.length > 64) fail('entries must be an array of at most 64');
  const seenProvider = new Set();
  const seenApp = new Set();
  for (const [i, e] of payload.entries.entries()) {
    const at = `entries[${i}]`;
    if (e === null || typeof e !== 'object') fail(`${at} must be an object`);
    if (typeof e.providerId !== 'string' || !PROVIDER_ID.test(e.providerId)) fail(`${at}.providerId`);
    if (!KINDS.includes(e.kind)) fail(`${at}.kind`);
    if (typeof e.applicationId !== 'string' || !APPLICATION_ID.test(e.applicationId)) fail(`${at}.applicationId`);
    if (typeof e.versionName !== 'string' || !e.versionName.trim() || e.versionName.length > 64 || CONTROL.test(e.versionName)) fail(`${at}.versionName`);
    if (!isInt(e.versionCode) || e.versionCode < 1) fail(`${at}.versionCode`);
    if (!isInt(e.minSdk) || e.minSdk < 1 || e.minSdk > 1000) fail(`${at}.minSdk`);
    if (!isInt(e.minStoreProtocol) || e.minStoreProtocol < 1) fail(`${at}.minStoreProtocol`);
    if (typeof e.sourceCommit !== 'string' || !HEX40.test(e.sourceCommit) || allZero(e.sourceCommit)) fail(`${at}.sourceCommit`);
    if (e.releaseNotes !== undefined && (typeof e.releaseNotes !== 'string' || e.releaseNotes.length > 4000 || CONTROL.test(e.releaseNotes))) fail(`${at}.releaseNotes`);
    const a = e.artifact;
    if (a === null || typeof a !== 'object') fail(`${at}.artifact`);
    if (!Array.isArray(a.urls) || a.urls.length < 1 || a.urls.length > 8) fail(`${at}.artifact.urls`);
    for (const raw of a.urls) {
      let url;
      try {
        url = new URL(raw);
      } catch {
        fail(`${at}.artifact.urls: not a URL`);
      }
      if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || url.hash) fail(`${at}.artifact.urls: must be plain https`);
      if (!hosts.includes(url.hostname)) fail(`${at}.artifact.urls: host ${url.hostname} not in trustedHosts`);
    }
    if (!isInt(a.bytes) || a.bytes < 1 || a.bytes > MAX_ARTIFACT_BYTES) fail(`${at}.artifact.bytes`);
    if (typeof a.sha256 !== 'string' || !HEX64.test(a.sha256) || allZero(a.sha256)) fail(`${at}.artifact.sha256`);
    if (!Array.isArray(a.signerSha256) || a.signerSha256.length < 1 || a.signerSha256.length > 4 || !a.signerSha256.every((s) => typeof s === 'string' && HEX64.test(s) && !allZero(s))) {
      fail(`${at}.artifact.signerSha256`);
    }
    if (seenProvider.has(e.providerId) || seenApp.has(e.applicationId)) fail(`${at} duplicates an earlier entry`);
    seenProvider.add(e.providerId);
    seenApp.add(e.applicationId);
  }
}

function requireP256(key, kind) {
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    fail(`${kind} key must be ECDSA P-256`);
  }
}

/** Signs a validated payload object; returns the envelope object. */
export function signPayload(payload, privateKeyPem, keyId) {
  if (typeof keyId !== 'string' || !KEY_ID.test(keyId)) fail('invalid keyId');
  validatePayload(payload);
  const key = crypto.createPrivateKey(privateKeyPem);
  requireP256(key, 'signing');
  const payloadBytes = Buffer.from(JSON.stringify(payload), 'utf8');
  const signature = crypto.sign('sha256', Buffer.concat([DOMAIN, payloadBytes]), { key, dsaEncoding: 'der' });
  const envelope = {
    envelopeVersion: ENVELOPE_VERSION,
    keyId,
    algorithm: ALGORITHM,
    payload: payloadBytes.toString('base64'),
    signature: signature.toString('base64'),
  };
  // Self-check with the derived public key before anything is written.
  verifyEnvelope(envelope, { [keyId]: crypto.createPublicKey(key).export({ type: 'spki', format: 'pem' }) });
  return envelope;
}

/** Verifies an envelope against {keyId: publicKeyPem}; returns the parsed payload. */
export function verifyEnvelope(envelope, publicKeys) {
  if (envelope?.envelopeVersion !== ENVELOPE_VERSION) fail('unsupported envelope');
  if (envelope.algorithm !== ALGORITHM) fail('unsupported algorithm');
  const encoded = Object.hasOwn(publicKeys, envelope.keyId) ? publicKeys[envelope.keyId] : undefined;
  if (!encoded) fail(`unknown keyId ${envelope.keyId}`);
  const key = toPublicKey(encoded);
  requireP256(key, 'verification');
  const payloadBytes = Buffer.from(envelope.payload, 'base64');
  if (payloadBytes.toString('base64') !== envelope.payload) fail('payload is not canonical base64');
  const ok = crypto.verify('sha256', Buffer.concat([DOMAIN, payloadBytes]), { key, dsaEncoding: 'der' }, Buffer.from(envelope.signature, 'base64'));
  if (!ok) fail('bad signature');
  const payload = JSON.parse(payloadBytes.toString('utf8'));
  validatePayload(payload);
  return payload;
}

/** Accepts a PEM public key or the base64 SPKI form used by release/catalog-trust.json and Bridge. */
export function toPublicKey(encoded) {
  if (encoded.includes('-----BEGIN')) return crypto.createPublicKey(encoded);
  return crypto.createPublicKey({ key: Buffer.from(encoded, 'base64'), format: 'der', type: 'spki' });
}

/** {keyId: key} from release/catalog-trust.json. */
export function trustKeys(trust) {
  if (trust?.keys === null || typeof trust?.keys !== 'object') fail('trust file has no keys object');
  return trust.keys;
}

export function sha256Hex(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}
