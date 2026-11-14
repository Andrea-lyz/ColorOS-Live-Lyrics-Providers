// Reads catalog facts from a built APK with the Android build-tools (aapt2 + apksigner), the
// same tools the existing release validators use. Parsing is split out so it can be tested
// without the SDK.
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

function fail(message) {
  throw new Error(`apk-facts: ${message}`);
}

export function parseBadging(text) {
  const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/m.exec(text);
  if (!pkg) fail('package line missing in aapt2 badging');
  const sdk = /^(?:minSdkVersion|sdkVersion):'(\d+)'/m.exec(text);
  if (!sdk) fail('minSdk missing in aapt2 badging');
  return { applicationId: pkg[1], versionCode: Number(pkg[2]), versionName: pkg[3], minSdk: Number(sdk[1]) };
}

/**
 * All current signers. apksigner prints one "Signer #N certificate SHA-256 digest: <hex>" per
 * signer; tolerate CRLF, surrounding spaces, colon-separated hex and a "Signer (minSdkVersion=…)"
 * label for SDK-targeted v3.1 signers.
 */
export function parseSigners(text) {
  const out = String(text).replace(/\r/g, '');
  if (!/^\s*Verifies\s*$/m.test(out)) fail(`apksigner did not report "Verifies"${summary(out)}`);
  const signers = [...out.matchAll(/^[ \t]*Signer\b[^\n]*?certificate SHA-256 digest:[ \t]*([0-9a-fA-F:]+)[ \t]*$/gm)]
    .map((m) => m[1].replace(/:/g, '').toLowerCase())
    .filter((hex) => /^[0-9a-f]{64}$/.test(hex));
  if (signers.length === 0) fail(`no signer certificate in apksigner output${summary(out)}`);
  return [...new Set(signers)];
}

// Only verification and certificate lines (public facts) go into an error, never the whole output.
function summary(text) {
  const lines = text.split('\n')
    .filter((line) => /^\s*(Verif|Number of signers|Signer|DOES NOT|ERROR|WARNING)/.test(line))
    .slice(0, 20);
  return lines.length ? `:\n${lines.join('\n')}` : ' (no verification lines)';
}

export function findBuildTools(explicit) {
  const candidates = [];
  if (explicit) candidates.push(explicit);
  for (const root of [process.env.ANDROID_SDK_ROOT, process.env.ANDROID_HOME].filter(Boolean)) {
    const dir = path.join(root, 'build-tools');
    if (fs.existsSync(dir)) {
      const versions = fs.readdirSync(dir).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
      candidates.push(...versions.map((v) => path.join(dir, v)));
    }
  }
  const windows = process.platform === 'win32';
  for (const dir of candidates) {
    const aapt2 = path.join(dir, windows ? 'aapt2.exe' : 'aapt2');
    const apksigner = path.join(dir, windows ? 'apksigner.bat' : 'apksigner');
    if (fs.existsSync(aapt2) && fs.existsSync(apksigner)) return { aapt2, apksigner };
  }
  fail('Android build-tools with aapt2 and apksigner not found; pass --build-tools');
}

function run(tool, args) {
  const options = { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] };
  if (!tool.endsWith('.bat')) return execFileSync(tool, args, options);
  // Windows refuses to spawn .bat directly; go through cmd with every argument quoted.
  for (const arg of [tool, ...args]) {
    if (/["%^&|<>]/.test(arg)) fail(`refusing shell-unsafe argument ${arg}`);
  }
  const command = [tool, ...args].map((arg) => `"${arg}"`).join(' ');
  return execFileSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', `"${command}"`], { ...options, windowsVerbatimArguments: true });
}

export function readApkFacts(apkPath, tools) {
  const bytes = fs.readFileSync(apkPath);
  return {
    ...parseBadging(run(tools.aapt2, ['dump', 'badging', apkPath])),
    bytes: bytes.length,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    signerSha256: signersOf(tools.apksigner, apkPath),
  };
}

function signersOf(apksigner, apkPath) {
  try {
    return parseSigners(run(apksigner, ['verify', '--verbose', '--print-certs', apkPath]));
  } catch (error) {
    throw new Error(`${error.message}\n(apksigner: ${apksigner})`);
  }
}
