#!/usr/bin/env node
// Maps a per-Provider release tag (<module>-v<versionName>, plan §14.5.2) to the module it
// releases, checked against the machine matrix. Prints JSON; with --github-output also appends
// key=value lines for GitHub Actions.
//
//   node scripts/catalog/resolve-release-tag.mjs --tag player-salt-v1.1.3 [--github-output "$GITHUB_OUTPUT"]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function fail(message) {
  throw new Error(`release-tag: ${message}`);
}

const safe = (text) => text.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '');

export function resolveReleaseTag(tag, matrix) {
  if (typeof tag !== 'string' || !/^[a-z0-9][a-z0-9-]*-v[0-9][0-9A-Za-z.-]*$/.test(tag)) fail(`not a Provider release tag: ${tag}`);
  const candidates = [
    ...(matrix.providers ?? []).map((p) => ({ ...p, kind: p.module === 'universal-provider' ? 'universal-lyrics' : 'dedicated-lyrics' })),
    ...(matrix.artworkProviders ?? []).map((a) => ({ ...a, kind: 'artwork' })),
  ];
  const match = candidates.find((c) => tag === `${c.module}-v${c.versionName}`);
  if (!match) {
    const module = candidates.find((c) => tag.startsWith(`${c.module}-v`));
    if (module) fail(`${tag} does not match the matrix version ${module.module}-v${module.versionName}; bump the matrix first`);
    fail(`${tag} names no module in the matrix`);
  }
  const assetName = match.kind === 'artwork'
    ? `ColorOS-Live-Lyrics-${safe(match.displayName)}-v${match.versionName}.apk`
    : `ColorOS-Live-Lyrics-Provider-${safe(match.displayName)}-v${match.versionName}.apk`;
  return {
    tag,
    module: match.module,
    kind: match.kind,
    applicationId: match.applicationId,
    versionName: match.versionName,
    versionCode: match.versionCode,
    assetName,
    gradleAssemble: `:${match.module}:assembleRelease`,
    builtApkGlob: `${match.module}/build/outputs/apk/release/*.apk`,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const argv = process.argv.slice(2);
    const a = {};
    for (let i = 0; i < argv.length; i += 2) a[argv[i].replace(/^--/, '')] = argv[i + 1];
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
    const matrix = JSON.parse(fs.readFileSync(a.matrix ?? path.join(root, 'release', 'v5-provider-matrix.json'), 'utf8'));
    const resolved = resolveReleaseTag(a.tag, matrix);
    if (a['github-output']) {
      fs.appendFileSync(a['github-output'], Object.entries(resolved).map(([k, v]) => `${k}=${v}\n`).join(''));
    }
    console.log(JSON.stringify(resolved));
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
