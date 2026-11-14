#!/usr/bin/env node
// Builds the next unsigned catalog payload from the matrix and freshly built APKs.
//
//   node scripts/catalog/build-catalog-payload.mjs \
//     --repo Andrea-lyz/ColorOS-Live-Lyrics-Providers --source-commit <sha> --expected-signer <sha256> \
//     --apk player-salt=path/to/Salt.apk [--apk ...] [--previous catalog.json --previous-key-id <id> --previous-public-key <pem>] \
//     [--notes player-salt=notes.txt] [--build-tools <dir>] [--now <epochSeconds>] --out catalog-payload.json
//
// --previous takes the currently published *signed* catalog and verifies it first, so a new
// revision can only be built on top of an authentic one.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyEnvelope, trustKeys } from './catalog-envelope.mjs';
import { buildPayload } from './catalog-payload.mjs';
import { findBuildTools, readApkFacts } from './apk-facts.mjs';

function parseArgs(argv) {
  const single = {};
  const multi = { apk: [], notes: [] };
  for (let i = 0; i < argv.length; i += 2) {
    const name = argv[i]?.replace(/^--/, '');
    const value = argv[i + 1];
    if (!argv[i]?.startsWith('--') || value === undefined) throw new Error(`bad argument ${argv[i]}`);
    if (name in multi) multi[name].push(value);
    else single[name] = value;
  }
  return { ...single, ...multi };
}

const pair = (text) => {
  const at = text.indexOf('=');
  if (at <= 0) throw new Error(`expected module=path, got ${text}`);
  return [text.slice(0, at), text.slice(at + 1)];
};

try {
  const a = parseArgs(process.argv.slice(2));
  for (const required of ['repo', 'source-commit', 'expected-signer', 'out']) {
    if (!a[required]) throw new Error(`missing --${required}`);
  }
  if (a.apk.length === 0) throw new Error('at least one --apk module=path');
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const matrix = JSON.parse(fs.readFileSync(a.matrix ?? path.join(root, 'release', 'v5-provider-matrix.json'), 'utf8'));

  let previous = null;
  if (a.previous) {
    let keys;
    if (a.trust) keys = trustKeys(JSON.parse(fs.readFileSync(a.trust, 'utf8')));
    else if (a['previous-key-id'] && a['previous-public-key']) keys = { [a['previous-key-id']]: fs.readFileSync(a['previous-public-key'], 'utf8') };
    else throw new Error('--previous needs --trust <catalog-trust.json> or --previous-key-id with --previous-public-key');
    previous = verifyEnvelope(JSON.parse(fs.readFileSync(a.previous, 'utf8')), keys);
  }

  const tools = findBuildTools(a['build-tools']);
  const notes = new Map(a.notes.map(pair));
  const updates = a.apk.map(pair).map(([module, apk]) => ({
    module,
    assetName: path.basename(apk),
    facts: readApkFacts(apk, tools),
    ...(notes.has(module) ? { releaseNotes: fs.readFileSync(notes.get(module), 'utf8').replace(/\r\n/g, '\n').trimEnd() } : {}),
  }));

  const payload = buildPayload({
    matrix,
    previous,
    updates,
    options: {
      repo: a.repo,
      sourceCommit: a['source-commit'],
      expectedSigner: a['expected-signer'].toLowerCase(),
      nowEpochSeconds: a.now ? Number(a.now) : Math.floor(Date.now() / 1000),
    },
  });
  fs.writeFileSync(a.out, `${JSON.stringify(payload, null, 2)}\n`, { flag: 'wx' });
  console.log(JSON.stringify({
    out: a.out,
    catalogRevision: payload.catalogRevision,
    entries: payload.entries.map((e) => `${e.providerId}@${e.versionName}/${e.versionCode}`),
  }));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
