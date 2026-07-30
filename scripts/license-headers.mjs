#!/usr/bin/env node
// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// Applies or verifies SPDX headers on first-party source files. Documentation,
// configuration, generated resources, and non-commentable assets are covered
// without content changes through REUSE.toml.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { extname } from 'node:path';

const check = process.argv.includes('--check');
const copyrightMarker = 'SPDX-FileCopyrightText:';
const licenseMarker = 'SPDX-' + 'License-Identifier:';
const copyright = `${copyrightMarker} Epistola Nederland B.V.`;
const license = `${licenseMarker} EUPL-1.2`;

const lineComments = new Map([
  ['.cjs', '//'],
  ['.gradle', '//'],
  ['.java', '//'],
  ['.js', '//'],
  ['.kt', '//'],
  ['.kts', '//'],
  ['.mjs', '//'],
  ['.sh', '#'],
  ['.ts', '//'],
]);

const blockComments = new Map([['.scss', ['/*', ' *', ' */']]]);
const specialFiles = new Map([
  ['test-app/backend/Dockerfile', '#'],
  ['test-app/frontend/Dockerfile', '#'],
]);
const skippedFiles = new Set([
  'backend/plugin/src/test/java/app/epistola/valtimo/BaseIntegrationTest.java',
  'backend/plugin/src/test/java/app/epistola/valtimo/PostgresTestContainerConfig.java',
  'test-app/frontend/src/assets/bpmn/ValtimoBPMNModeler.js',
]);
const skippedPrefixes = ['.claude/', '.github/', 'LICENSES/'];

function trackedFiles() {
  return execFileSync('git', ['ls-files', '-z']).toString().split('\0').filter(Boolean);
}

function headerFor(file) {
  const special = specialFiles.get(file);
  if (special) return `${special} ${copyright}\n${special}\n${special} ${license}\n\n`;

  const line = lineComments.get(extname(file));
  if (line) return `${line} ${copyright}\n${line}\n${line} ${license}\n\n`;

  const block = blockComments.get(extname(file));
  if (block) {
    const [open, prefix, close] = block;
    return `${open}\n${prefix} ${copyright}\n${prefix}\n${prefix} ${license}\n${close}\n\n`;
  }

  return null;
}

function insertionOffset(content) {
  if (!content.startsWith('#!')) return 0;
  const newline = content.indexOf('\n');
  return newline === -1 ? content.length : newline + 1;
}

function hasLicenseMetadata(content) {
  const head = content.split('\n').slice(0, 25).join('\n');
  const hasCopyright = head.includes(copyrightMarker) || /\bCopyright\b/.test(head);
  return hasCopyright && head.includes(licenseMarker);
}

const files = trackedFiles().filter(
  (file) =>
    !skippedFiles.has(file) &&
    !skippedPrefixes.some((prefix) => file.startsWith(prefix)) &&
    headerFor(file) !== null,
);
const missing = [];

for (const file of files) {
  const content = readFileSync(file, 'utf8');
  if (hasLicenseMetadata(content)) continue;

  missing.push(file);
  if (!check) {
    const offset = insertionOffset(content);
    writeFileSync(file, `${content.slice(0, offset)}${headerFor(file)}${content.slice(offset)}`);
  }
}

if (check && missing.length) {
  console.error(`SPDX license metadata missing in ${missing.length} file(s):`);
  for (const file of missing) console.error(`  ${file}`);
  console.error('\nRun `pnpm license:headers` to insert it.');
  process.exit(1);
}

console.log(
  check
    ? `SPDX license metadata present in all ${files.length} first-party source file(s).`
    : missing.length
      ? `Inserted SPDX license metadata into ${missing.length} file(s).`
      : `SPDX license metadata already present in all ${files.length} first-party source file(s).`,
);
