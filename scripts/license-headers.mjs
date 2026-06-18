#!/usr/bin/env node
// Applies / verifies the canonical EUPL-1.2 license header on the published
// frontend TypeScript sources. Scoped deliberately to frontend/plugin/src so it
// never touches node_modules, build output, or unrelated files. Shares the
// header text with the backend Spotless rule (config/license-header.txt) so the
// two ecosystems never diverge.
//
//   node scripts/license-headers.mjs          # insert header where missing
//   node scripts/license-headers.mjs --check  # fail (exit 1) if any file lacks it
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const targetDir = join(repoRoot, 'frontend', 'plugin', 'src');
const header = readFileSync(join(repoRoot, 'config', 'license-header.txt'), 'utf8').trimEnd();
const check = process.argv.includes('--check');

/** Recursively collect .ts files (excluding generated .d.ts). */
function collect(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...collect(full));
    } else if (entry.endsWith('.ts') && !entry.endsWith('.d.ts')) {
      out.push(full);
    }
  }
  return out;
}

const files = collect(targetDir);
const missing = [];

for (const file of files) {
  const content = readFileSync(file, 'utf8');
  // Header is considered present if the file starts with the exact block.
  if (content.startsWith(header)) continue;
  missing.push(file);
  if (!check) {
    writeFileSync(file, `${header}\n\n${content.replace(/^\s+/, '')}`);
  }
}

if (check) {
  if (missing.length) {
    console.error(`License header missing in ${missing.length} file(s):`);
    for (const f of missing) console.error(`  ${f.replace(`${repoRoot}/`, '')}`);
    console.error('\nRun `pnpm headers` to insert them.');
    process.exit(1);
  }
  console.log(`License header present in all ${files.length} file(s).`);
} else {
  console.log(
    missing.length
      ? `Inserted license header into ${missing.length} file(s).`
      : `License header already present in all ${files.length} file(s).`,
  );
}
