#!/usr/bin/env node
// Generates one ECDSA P-256 catalog signing key pair for the maintainer to run locally.
// The private key is written to --out (refuses to overwrite) and is never printed. The
// public key is printed as base64 SPKI for release/catalog-trust.json and Bridge
// CatalogTrust.PRODUCTION_CATALOG_KEYS. Generate an active and an offline backup key.
//
//   node scripts/catalog/generate-catalog-key.mjs --key-id cll-catalog-2026a --out <outside the repo>/cll-catalog-2026a.pem
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

try {
  const argv = process.argv.slice(2);
  const a = {};
  for (let i = 0; i < argv.length; i += 2) a[argv[i].replace(/^--/, '')] = argv[i + 1];
  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(a['key-id'] ?? '') || a['key-id'].startsWith('test-')) {
    throw new Error('--key-id must be a lowercase id and must not start with test-');
  }
  if (!a.out) throw new Error('--out <private key path outside the repository>');
  const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const out = path.resolve(a.out);
  if (out === repo || out.startsWith(repo + path.sep)) throw new Error('refusing to write a private key inside the repository');
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  fs.writeFileSync(out, privateKey.export({ type: 'pkcs8', format: 'pem' }), { flag: 'wx', mode: 0o600 });
  console.log(JSON.stringify({
    keyId: a['key-id'],
    privateKeyWrittenTo: out,
    publicKeySpkiBase64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  }, null, 2));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
