#!/usr/bin/env node
// Signs a store catalog payload. The private key comes from --key <pem file> or, in the
// protected GitHub Environment, from the CLL_CATALOG_SIGNING_KEY secret (PEM text).
// The key is never printed, logged or written anywhere else.
//
//   node scripts/catalog/sign-catalog.mjs --payload catalog-payload.json --key-id <id> --out catalog.json [--key key.pem]
import fs from 'node:fs';
import { signPayload, sha256Hex } from './catalog-envelope.mjs';

function args(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 2) {
    const name = argv[i];
    if (!name?.startsWith('--') || argv[i + 1] === undefined) throw new Error(`bad argument ${name}`);
    out[name.slice(2)] = argv[i + 1];
  }
  return out;
}

try {
  const a = args(process.argv.slice(2));
  if (!a.payload || !a['key-id'] || !a.out) throw new Error('usage: --payload <file> --key-id <id> --out <file> [--key <pem>]');
  const pem = a.key ? fs.readFileSync(a.key, 'utf8') : process.env.CLL_CATALOG_SIGNING_KEY;
  if (!pem) throw new Error('no signing key: pass --key or set CLL_CATALOG_SIGNING_KEY');
  const payload = JSON.parse(fs.readFileSync(a.payload, 'utf8'));
  const envelope = signPayload(payload, pem, a['key-id']);
  const text = `${JSON.stringify(envelope, null, 2)}\n`;
  fs.writeFileSync(a.out, text, { flag: 'wx' });
  console.log(JSON.stringify({
    out: a.out,
    keyId: envelope.keyId,
    catalogRevision: payload.catalogRevision,
    entries: payload.entries.length,
    payloadSha256: sha256Hex(Buffer.from(envelope.payload, 'base64')),
  }));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
