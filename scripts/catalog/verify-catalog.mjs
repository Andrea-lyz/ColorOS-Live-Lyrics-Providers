#!/usr/bin/env node
// Verifies a signed store catalog.
//
//   node scripts/catalog/verify-catalog.mjs --envelope catalog.json --trust release/catalog-trust.json
//   node scripts/catalog/verify-catalog.mjs --envelope catalog.json --key-id <id> --public-key key.pub.pem
import fs from 'node:fs';
import { verifyEnvelope, sha256Hex, trustKeys } from './catalog-envelope.mjs';

try {
  const a = {};
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i += 2) a[argv[i].replace(/^--/, '')] = argv[i + 1];
  if (!a.envelope) throw new Error('usage: --envelope <file> (--trust <json> | --key-id <id> --public-key <pem>)');
  let keys;
  if (a.trust) keys = trustKeys(JSON.parse(fs.readFileSync(a.trust, 'utf8')));
  else if (a['key-id'] && a['public-key']) keys = { [a['key-id']]: fs.readFileSync(a['public-key'], 'utf8') };
  else throw new Error('pass --trust or --key-id with --public-key');
  const envelope = JSON.parse(fs.readFileSync(a.envelope, 'utf8'));
  const payload = verifyEnvelope(envelope, keys);
  console.log(JSON.stringify({
    ok: true,
    keyId: envelope.keyId,
    catalogRevision: payload.catalogRevision,
    channel: payload.channel,
    expiresAtEpochSeconds: payload.expiresAtEpochSeconds,
    entries: payload.entries.length,
    payloadSha256: sha256Hex(Buffer.from(envelope.payload, 'base64')),
  }));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
